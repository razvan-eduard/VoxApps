package com.voxapps.expenses.domain.llm

import com.voxapps.expenses.data.ExpenseSuggestionTarget
import android.content.Context
import com.voxapps.expenses.data.Expense
import com.voxapps.expenses.data.ExpenseSource
import com.voxapps.expenses.data.FieldVocabularies
import com.voxapps.expenses.data.TransactionDirection
import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.expenses.data.ExpenseOrigins
import com.voxapps.recordflow.FieldOrigin
import com.voxapps.expenses.di.ExpensesContainer
import com.voxapps.ipc.VoxCapabilityClient
import com.voxapps.logging.Logger
import com.voxapps.recordflow.AskScope
import com.voxapps.recordflow.DeterministicReading
import com.voxapps.recordflow.FieldWeight
import com.voxapps.recordflow.FlowSupport
import com.voxapps.recordflow.RecordFlowSpec
import com.voxapps.recordflow.RecordSource
import kotlinx.coroutines.flow.first

private const val TAG = "NotificationExpenseFlow"

/**
 * Containment, so a shop's fuller registered spelling and its bare name reach each other in both
 * directions. Looser than anything allowed to claim a field: a wrong answer here is a rename shown
 * to a person, who declines it by not touching it, rather than a field silently mislabelled.
 */
private const val RENAME_MATCH_LEVEL = 2

/**
 * What the model said, unless it said nothing.
 *
 * Blank counts as nothing, which is the whole point: a model with no answer for a field supplies an
 * empty string as readily as it omits the key, and `?:` catches only the omission. A reply carrying
 * `"vendor": ""` therefore used to overwrite a vendor the device had already read out of the
 * notification's own characters — leaving a record anonymous enough to be held back from a user who
 * had asked for it to be filed.
 */
private fun String?.orRead(fallback: String?): String? =
    this?.takeIf { it.isNotBlank() } ?: fallback

/**
 * What a captured notification yields before anything is asked: the figures, and whatever a person
 * has already taught about this exact sentence shape.
 */
data class CapturedNotification(
    val title: String?,
    val text: String?,
    val amount: Double?,
    val vendor: String?,
    val bank: String?,
    /** The currency the message states, read from its own characters. It outranks the model's
     *  answer for the same reason the amount does: it was never in doubt. */
    val currency: String?,
    val templateHash: String?,
    val direction: TransactionDirection?,
    val knownPayment: Boolean,
    /** The source package carries the person's banking star — their standing statement that this
     *  app announces their own money moving. A figure arriving from it is a payment by
     *  declaration, not by inference. */
    val fromStarredBank: Boolean = false,
    /** The system delivered this notification with its body withheld (its code-protection guard).
     *  There was never a text to read — a different fact from a text that carried no figure. */
    val redacted: Boolean = false,
    /** The source notification's key, carried so a redacted stub can point back at the shade copy
     *  it will be recovered from — and dismiss it once recovered. */
    val sourceKey: String? = null
)

/**
 * A payment notification becoming an expense.
 *
 * The narrowest of the three inputs, and the one where the difference between "present" and "proved"
 * matters most. A notification is one sentence: its figures can be taken without interpretation, but
 * what the sentence *means* — whether money left the account or arrived in it — is not in the
 * figures, and no rule written for it survives contact with the next bank's wording.
 *
 * So it is never guessed. The template memory answers where a person has already confirmed this
 * sentence shape, and that is the only thing that makes a reading complete here. Everything short of
 * it waits in the review queue — which is also how the memory learns, since approving an entry
 * confirms its template and the next message of that shape needs no one.
 *
 * Both halves run through here. What stays outside is what is about the *request* rather than the
 * record: whether a failed reply is worth retrying, and marking the notification handled — both
 * belong to the queue that sent it, not to what is written afterwards.
 */
class NotificationExpenseFlow(
    private val context: Context,
    private val container: ExpensesContainer,
    /** What was resolved before the question was asked, kept aside so the reply can be reunited with
     *  it. Null while dispatching, since nothing has been asked yet. */
    private val suppressed: ScanPreParse? = null,
    /**
     * The bank this app resolved deterministically, carried through the request rather than trusted
     * to come back: the prompt asks the model to echo it character-for-character, which is exactly
     * the kind of instruction a model drops or garbles.
     */
    private val knownBank: String? = null
) : RecordFlowSpec<CapturedNotification, CapturedNotification, NotificationExpenseParseResultParser.Parsed> {

    override val source = RecordSource.NOTIFICATION
    override val support: FlowSupport = ExpensesSettings.NOTIFICATION_FLOW_SUPPORT
    override val taskId = LlmTasks.NOTIFICATION_EXPENSE_PARSE

    /**
     * Whether this capture ended up anywhere, and where.
     *
     * [com.voxapps.recordflow.RecordFlow.Outcome] cannot answer this on its own: a commit that
     * queued for review returns null, which reads as `Discarded` from outside even though the
     * capture was kept. The distinction only matters to a caller about to do something
     * irreversible to the source notification, so it is recorded here rather than widened into
     * the shared contract — and NOTHING is the answer until something says otherwise.
     */
    enum class Kept { NOTHING, REVIEW, RECORD }

    var kept: Kept = Kept.NOTHING
        private set

    override suspend fun read(input: CapturedNotification): DeterministicReading<CapturedNotification> {
        val hasAmount = input.amount != null && input.amount > 0.0
        val settings = container.settingsRepository.getSnapshot()
        val assumed = ExpensesSettings.assumedDirectionOf(settings.notificationAssumedDirection)
        return DeterministicReading(
            fields = input,
            // An amount is what tells a payment from an advertisement, so by default a message
            // without one is nothing to keep — that, rather than a list of rules, is what makes a
            // promotional message harmless. Some senders do announce a payment and leave the sum
            // out, though, and for those the capture is worth keeping unfinished; it is opt-in
            // because the cost of being wrong is every promotion landing in review.
            // A message the system delivered gutted is also worth keeping: there is no figure to
            // find because the OS withheld the body, and the review list is where a person supplies
            // what the device was never shown.
            usable = hasAmount || input.redacted || settings.captureAmountlessPayments,
            // Proved means: how much, and which way the money went. A shape a person has taught
            // says so itself; otherwise this flow assumes nothing and waits — unless the user has
            // said what to assume, which is the only thing that lets an untaught shape through.
            // Never complete without a figure, whatever else is known: an assumption may supply a
            // direction, but nothing here may supply the sum.
            complete = hasAmount && when {
                input.knownPayment -> input.direction == TransactionDirection.OUTGOING
                // The banking star is the person's own standing answer for the whole source: an app
                // they marked as their bank announcing a figure is a payment to file, vendor or no
                // vendor. A taught shape still outranks it — teaching is per-sentence, the star is
                // per-app, and the more specific statement wins.
                input.fromStarredBank -> true
                else -> assumed != null
            }
        )
    }

    /**
     * What to write when nothing has been taught, or null to wait for a person.
     *
     * Only ever consulted for a shape the memory has no verdict on: a taught template outranks it,
     * and so does a direction the model returned. The assumption is safe to offer only because it
     * is not final — [commit] links the record to the shape that produced it, so correcting the
     * direction once is the confirmation that shape never had.
     */
    private suspend fun assumedDirection(): TransactionDirection? =
        ExpensesSettings.assumedDirectionOf(
            container.settingsRepository.getSnapshot().notificationAssumedDirection
        )

    override suspend fun prompt(reading: DeterministicReading<CapturedNotification>, asks: AskScope): String {
        val f = reading.fields
        val settings = container.settingsRepository.getSnapshot()
        val existingCategories = container.expensesRepository.categories.first().map { it.name }
        // Whatever the device settled is suppressed from the question rather than asked again — the
        // narrower rungs are the whole reason the reading is handed in here.
        return NotificationExpenseParsePromptBuilder.build(
            notificationTitle = f.title,
            notificationText = f.text,
            existingCategories = existingCategories,
            // The fallback for a message that stated none; what it did state is suppressed below
            // rather than handed over as a default to echo.
            defaultCurrency = settings.defaultCurrency,
            languageCode = settings.language,
            knownBankName = f.bank,
            isLocalEngine = VoxCapabilityClient.isLocalEngine(context.applicationContext),
            preParsedAmount = f.amount,
            preParsedCurrency = f.currency,
            preParsedVendor = f.vendor,
            preParsedDirection = f.direction?.toJsonValue(),
            preKnownPayment = f.knownPayment
        )
    }

    /**
     * The notification parser rather than the general one: a reply here may say "not a payment",
     * and it is read against what was suppressed from the question — see [suppressed], which is per
     * capture and so lives on this object, being the only thing present in both halves.
     */
    override suspend fun parse(reply: String): NotificationExpenseParseResultParser.Parsed? =
        NotificationExpenseParseResultParser.parse(
            reply,
            presetAmount = suppressed?.total,
            presetIsPayment = suppressed?.isPaymentKnown == true
        )?.let { answer ->
            // A vendor read from the notification's own characters outranks anything produced for
            // it, and with the vendor suppressed from the prompt a model-invented title names
            // whatever text was left — the card, the bank. So the title is composed, not modelled.
            val vendor = suppressed?.vendor ?: return@let answer
            answer.copy(
                vendor = vendor,
                title = NotificationPreParse.composeTitle(vendor, answer.category)
            )
        }?.let { answer ->
            // A direction inherited from the template memory outranks the model's: a human
            // classified this exact sentence shape, twice.
            when (suppressed?.direction?.lowercase()) {
                "incoming" -> answer.copy(direction = TransactionDirection.INCOMING)
                "outgoing" -> answer.copy(direction = TransactionDirection.OUTGOING)
                else -> answer
            }
        }

    /**
     * What the answer said and the record did not take.
     *
     * A rung that writes none of the answer still heard it, and throwing it away would make the
     * round trip pointless — so it waits on the record as a chip somebody can accept. The amount is
     * offered only where the message itself proved none: where it did, the answer is not a second
     * opinion worth showing, it is a misreading of a number that was already legible.
     */
    private suspend fun offerWhatWasNotWritten(
        recordId: Long,
        parsed: NotificationExpenseParseResultParser.Parsed?,
        headApplied: Boolean,
        amountProved: Boolean
    ) {
        if (parsed == null || (headApplied && amountProved)) return
        val offerHead = !headApplied
        container.suggestionStore.offer(
            recordId,
            mapOf(
                ExpenseSuggestionTarget.KEY_TITLE to parsed.title.takeIf { offerHead },
                ExpenseSuggestionTarget.KEY_VENDOR to parsed.vendor.takeIf { offerHead },
                ExpenseSuggestionTarget.KEY_BANK to parsed.bank.takeIf { offerHead },
                ExpenseSuggestionTarget.KEY_CATEGORY to parsed.category.takeIf { offerHead },
                ExpenseSuggestionTarget.KEY_CURRENCY to parsed.currency.takeIf { offerHead },
                ExpenseSuggestionTarget.KEY_AMOUNT to parsed.totalAmount
                    ?.takeIf { amountProved }?.toString()
            ).filterValues { it != null }
        )
    }

    /**
     * Nothing here is deliberate on anyone's part — no one pressed anything — so a record is only
     * ever written where the person has already said what this kind of message means, and asked for
     * those to be filed.
     */
    override suspend fun autoAcceptWhenProven(): Boolean =
        container.settingsRepository.getSnapshot().autoAcceptNotificationExpenses

    override suspend fun commit(
        reading: DeterministicReading<CapturedNotification>?,
        parsed: NotificationExpenseParseResultParser.Parsed?,
        applies: (FieldWeight) -> Boolean
    ): Long? {
        val settings = container.settingsRepository.getSnapshot()
        val f = reading?.fields
        val category = container.expensesRepository.defaultCategory()
        // Which side this rung trusts for the names — the same gate the scan honours. A level that
        // says "send everything, write nothing" has to mean it here too: the answer arrives, and
        // what it says goes to the record only where it applies, otherwise it is offered.
        val headApplied = applies(FieldWeight.HEAD)
        val head = parsed?.takeIf { headApplied }
        // One record shape, whether the fields came from the message's own characters or from an
        // answer — the writing path is the same one every other capture goes through.
        val record = ExpenseParseResultParser.Parsed(
            title = head?.title.orRead(title(f?.vendor, f?.bank, category?.name)),
            // What the characters proved outranks what was answered. The figure is the most
            // certain thing a message carries — one number, under a rule that refuses anything
            // ambiguous — so a model that read it differently read it wrong.
            totalAmount = f?.amount ?: parsed?.totalAmount ?: return null,
            currency = f?.currency.orRead(head?.currency.orRead(settings.defaultCurrency)),
            vendor = head?.vendor.orRead(f?.vendor),
            bank = knownBank.orRead(head?.bank.orRead(f?.bank)),
            location = null,
            category = head?.category.orRead(category?.name),
            date = null,
            time = null,
            items = emptyList(),
            // Named rather than defaulted. Three sources, in order of what they are worth: a
            // direction the model returned, then one a person taught for this shape, then the
            // assumption — and if there is none, the type's own default stands, which is the case
            // this flow only reaches for a shape already confirmed as outgoing.
            direction = head?.direction
                ?: f?.direction
                ?: assumedDirection()
                ?: TransactionDirection.OUTGOING
        )
        // Two conditions, and the record is filed only when both hold.
        //
        // The parser already refuses a payment without an amount; identifiable is the other half of
        // that gate. A reply naming neither a title nor a vendor identifies nothing, and filing it
        // unseen leaves a record its owner can only puzzle over.
        //
        // And the setting decides whether an answer may be filed at all without being seen. It is
        // asked here rather than left to the policy because only the delivery half of this flow
        // reaches this point: the policy consults autoAcceptWhenProven when a capture is dispatched,
        // and a reply arriving later never passes through it. Losing that made every answered
        // notification file itself whatever the setting said.
        // Before anything is decided about this capture: it may be the second announcement of a
        // payment already filed, in which case it has nothing to create and everything to add.
        secondNoticeOf(record)?.let { foldedInto ->
            kept = Kept.RECORD
            return foldedInto
        }

        val identifiable = !record.title.isNullOrBlank() || !record.vendor.isNullOrBlank()
        if (parsed != null && !(identifiable && autoAcceptWhenProven())) {
            Logger.d(
                TAG,
                if (!identifiable) "The answer identifies nothing — queued rather than filed"
                else "Auto-accept is off — queued for approval"
            )
            queueForReview(reading, parsed)
            return null
        }
        val newId = com.voxapps.expenses.receiver.LlmResultReceiver().createExpenseFromParsed(
            appContext = context.applicationContext,
            container = container,
            parsed = record,
            imageName = null,
            preParse = null,
            // Both halves of the message: a wallet puts the shop in the title and the card in the
            // body, and which half carries the tail is not something to assume.
            sourceText = listOfNotNull(f?.title, f?.text).joinToString(" "),
            // The message's own characters proved the figures; a curated list recognised the names;
            // whatever neither settled is what the model was asked for.
            origins = mapOf(
                FieldOrigin.PROVED to buildSet {
                    if (f?.amount != null) add(ExpenseOrigins.FIELD_AMOUNT)
                    if (f?.currency != null) add(ExpenseOrigins.FIELD_CURRENCY)
                },
                FieldOrigin.MATCHED to buildSet {
                    if (f?.vendor != null) add(ExpenseOrigins.FIELD_VENDOR)
                    if (f?.bank != null || knownBank != null) add(ExpenseOrigins.FIELD_BANK)
                },
                FieldOrigin.ANSWERED to buildSet {
                    if (head?.title != null && f?.vendor == null) add(ExpenseOrigins.FIELD_TITLE)
                    if (f?.amount == null && parsed?.totalAmount != null) add(ExpenseOrigins.FIELD_AMOUNT)
                    if (f?.currency == null && head?.currency != null) add(ExpenseOrigins.FIELD_CURRENCY)
                    if (f?.vendor == null && head?.vendor != null) add(ExpenseOrigins.FIELD_VENDOR)
                    if (f?.bank == null && knownBank == null && head?.bank != null) add(ExpenseOrigins.FIELD_BANK)
                    if (head?.category != null) add(ExpenseOrigins.FIELD_CATEGORY)
                }
            )
        )
        kept = Kept.RECORD
        container.expensesStateManager.learnNamesFrom(record.vendor, record.bank, fromScan = false)
        offerWhatWasNotWritten(newId, parsed, headApplied, f?.amount != null)
        // The same link the model path writes, so editing the record still teaches its template.
        f?.templateHash?.let { container.templateDirectionMemory.linkRecord(newId, it) }
        Logger.d(
            TAG,
            if (parsed == null) "Created without a model: ${record.totalAmount} ${record.currency}"
            else "Created from a model's answer: ${record.totalAmount} ${record.currency}"
        )
        return newId
    }

    override suspend fun queueForReview(
        reading: DeterministicReading<CapturedNotification>?,
        parsed: NotificationExpenseParseResultParser.Parsed?
    ) {
        val settings = container.settingsRepository.getSnapshot()
        val f = reading?.fields
        // No early return on a missing amount any more: the entry is exactly what the review list
        // is for, and the figure is the one thing a person supplies there in a second.
        val amount = parsed?.totalAmount ?: f?.amount
        val category = container.expensesRepository.defaultCategory()
        // A payment already filed does not also belong in the review queue: the entry would be a
        // second copy of something the list already shows, asking to be approved into a third.
        if (amount != null) {
            val asRecord = candidateFor(amount, parsed, f, settings, category?.name)
            if (secondNoticeOf(asRecord) != null) {
                kept = Kept.RECORD
                Logger.d(TAG, "A second notice of a filed payment — nothing queued")
                return
            }
        }
        val vendorForEntry = parsed?.vendor.orRead(f?.vendor)
        val bankForEntry = knownBank.orRead(parsed?.bank.orRead(f?.bank))
        // Asked of the word the entry will actually show — the vendor if one was resolved, the line
        // offered as a candidate if none was. Either way it is the spelling a person is about to
        // read, and the only one worth pointing at a name they already use.
        val (vendorRename, bankRename) = renamesFor(
            vendorForEntry ?: f?.title?.trim()?.takeIf { it.isNotBlank() },
            bankForEntry,
            settings
        )
        container.pendingNotificationExpenseRepository.addPending(
            PendingNotificationExpense(
                id = System.currentTimeMillis(),
                title = parsed?.title.orRead(title(f?.vendor, f?.bank, category?.name))
                    ?: title(f?.vendor, f?.bank, category?.name),
                totalAmount = amount,
                currency = f?.currency.orRead(parsed?.currency.orRead(settings.defaultCurrency))
                    ?: settings.defaultCurrency,
                vendor = vendorForEntry,
                // Only where nothing identified a merchant. The title is where a wallet puts the
                // shop, so it is the line worth pointing at — but no list claimed it, so it is
                // offered as a question rather than written as an answer. It is also the spelling a
                // rename renames, which is why it is kept even when one is proposed.
                vendorCandidate = if (vendorForEntry == null) {
                    f?.title?.trim()?.takeIf { it.isNotBlank() }
                } else {
                    null
                },
                vendorRenameTo = vendorRename,
                bankRenameTo = bankRename,
                category = parsed?.category.orRead(category?.name),
                capturedAt = System.currentTimeMillis(),
                bank = bankForEntry,
                // The queue's own default, and the thing approving the entry confirms. A starting
                // position for the reviewer, not a claim about the message.
                direction = f?.direction ?: TransactionDirection.OUTGOING,
                templateHash = f?.templateHash,
                // A capture the platform delivered gutted keeps its link to the shade copy — the
                // one place the payment still exists whole, and the place it can be recovered from.
                redactedStub = f?.redacted == true,
                sourceKey = f?.sourceKey.takeIf { f?.redacted == true }
            )
        )
        kept = Kept.REVIEW
        Logger.d(TAG, "Queued for review: $amount")
    }

    /**
     * The accepted names a capture's own could be another spelling of, one per field.
     *
     * Two sources, because a name earns its standing two ways: a term someone put in a vocabulary
     * list, and a name they typed onto a record themselves. Both are answers a person gave; neither
     * is a guess, and a rename pointed at either lands on a name they already use.
     *
     * Asked only of a name that is not already listed, so nothing is proposed about a word the app
     * resolved outright. [FuzzyNameMatcher] level 2 — containment, so a shop's fuller registered
     * spelling and its bare name reach each other, which is the pairing this exists for.
     */
    private suspend fun renamesFor(vendor: String?, bank: String?, settings: ExpensesSettings): Pair<String?, String?> {
        if (vendor == null && bank == null) return null to null
        val lists = FieldVocabularies.vocabularies(context, settings).associate { it.name to it.terms }
        val records = container.expensesRepository.expensesSnapshot()
        fun acceptedFor(vocabulary: String, of: (Expense) -> String?): List<String> =
            (lists[vocabulary].orEmpty() + NameAlreadyKnown.vouchedNames(records, of)).distinct()
        return NameAlreadyKnown.match(
            vendor, acceptedFor(FieldVocabularies.VOCAB_VENDOR) { it.vendor }, RENAME_MATCH_LEVEL
        ) to NameAlreadyKnown.match(
            bank, acceptedFor(FieldVocabularies.VOCAB_BANK) { null }, RENAME_MATCH_LEVEL
        )
    }

    /**
     * The words known to be true, in the order they identify the record. Deliberately not a sentence
     * — a phrase would have to be composed, and composing is the one thing this path refuses to do.
     */
    private fun title(vendor: String?, bank: String?, category: String?): String? =
        listOfNotNull(
            vendor?.takeIf { it.isNotBlank() },
            bank?.takeIf { it.isNotBlank() && it != vendor },
            category?.takeIf { it.isNotBlank() }
        ).firstOrNull()

    /**
     * The id of the record this capture is another announcement of, or null when it is a payment of
     * its own. See [SecondNotice] for why this is asked here rather than left to the duplicate check.
     */
    private suspend fun secondNoticeOf(record: ExpenseParseResultParser.Parsed): Long? =
        record.totalAmount?.let {
            container.expensesRepository.foldSecondNotice(
                Expense(
                    title = record.title,
                    totalAmount = it,
                    currencyCode = record.currency ?: container.settingsRepository.getSnapshot().defaultCurrency,
                    vendor = record.vendor,
                    dateTime = System.currentTimeMillis(),
                    direction = record.direction,
                    source = ExpenseSource.NOTIFICATION
                )
            )
        }

    /** The same record shape the filing path builds, for a capture on its way to the review queue —
     *  so both paths ask the identical question of the identical thing. */
    private fun candidateFor(
        amount: Double,
        parsed: NotificationExpenseParseResultParser.Parsed?,
        f: CapturedNotification?,
        settings: ExpensesSettings,
        categoryName: String?
    ) = ExpenseParseResultParser.Parsed(
        title = parsed?.title.orRead(title(f?.vendor, f?.bank, categoryName)),
        totalAmount = amount,
        currency = f?.currency.orRead(parsed?.currency.orRead(settings.defaultCurrency)),
        vendor = parsed?.vendor.orRead(f?.vendor),
        bank = knownBank.orRead(parsed?.bank.orRead(f?.bank)),
        location = null,
        category = parsed?.category.orRead(categoryName),
        date = null,
        time = null,
        items = emptyList(),
        direction = parsed?.direction ?: f?.direction ?: TransactionDirection.OUTGOING
    )
}
