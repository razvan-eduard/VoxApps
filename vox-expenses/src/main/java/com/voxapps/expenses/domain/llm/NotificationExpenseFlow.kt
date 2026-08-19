package com.voxapps.expenses.domain.llm

import android.content.Context
import com.voxapps.expenses.data.TransactionDirection
import com.voxapps.expenses.data.preferences.ExpensesSettings
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
 * What a captured notification yields before anything is asked: the figures, and whatever a person
 * has already taught about this exact sentence shape.
 */
data class CapturedNotification(
    val title: String?,
    val text: String?,
    val amount: Double?,
    val vendor: String?,
    val bank: String?,
    val templateHash: String?,
    val direction: TransactionDirection?,
    val knownPayment: Boolean
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
 * **Only the dispatching half runs through this yet.** A reply still lands in
 * [com.voxapps.expenses.receiver.LlmResultReceiver]'s own branch, which carries decisions this class
 * does not have and must not lose: what counts as a retryable failure rather than a final "not a
 * payment", the refusal to auto-accept an answer that names neither a title nor a vendor, and the
 * location prefill. Those move here when they can be exercised end to end; until then [parse] and
 * the answered path of [commit] exist for the level machinery and are not the live delivery route.
 */
class NotificationExpenseFlow(
    private val context: Context,
    private val container: ExpensesContainer,
    /** What was resolved before the question was asked, kept aside so the reply can be reunited with
     *  it. Null while dispatching, since nothing has been asked yet. */
    private val suppressed: ScanPreParse? = null
) : RecordFlowSpec<CapturedNotification, CapturedNotification, NotificationExpenseParseResultParser.Parsed> {

    override val source = RecordSource.NOTIFICATION
    override val support: FlowSupport = ExpensesSettings.NOTIFICATION_FLOW_SUPPORT
    override val taskId = LlmTasks.NOTIFICATION_EXPENSE_PARSE

    override suspend fun read(input: CapturedNotification): DeterministicReading<CapturedNotification> {
        val hasAmount = input.amount != null && input.amount > 0.0
        return DeterministicReading(
            fields = input,
            // Below this there is no record to write and nothing to review — which is what makes a
            // promotional message harmless rather than something to be filtered out by rules.
            usable = hasAmount,
            // Proved means: how much, and that money went out. A direction nobody has confirmed is
            // exactly the thing this flow refuses to assume.
            complete = hasAmount &&
                input.knownPayment &&
                input.direction == TransactionDirection.OUTGOING
        )
    }

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
            defaultCurrency = settings.defaultCurrency,
            languageCode = settings.language,
            knownBankName = f.bank,
            isLocalEngine = VoxCapabilityClient.isLocalEngine(context.applicationContext),
            preParsedAmount = f.amount,
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
        )

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
        // One record shape, whether the fields came from the page's own characters or from an
        // answer — the writing path is the same one every other capture goes through.
        val record = ExpenseParseResultParser.Parsed(
            title = parsed?.title ?: title(f?.vendor, f?.bank, category?.name),
            totalAmount = parsed?.totalAmount ?: f?.amount ?: return null,
            currency = parsed?.currency ?: settings.defaultCurrency,
            vendor = parsed?.vendor ?: f?.vendor,
            bank = parsed?.bank ?: f?.bank,
            location = null,
            category = parsed?.category ?: category?.name,
            date = null,
            time = null,
            items = emptyList()
        )
        val newId = com.voxapps.expenses.receiver.LlmResultReceiver().createExpenseFromParsed(
            appContext = context.applicationContext,
            container = container,
            parsed = record,
            imageName = null,
            preParse = null
        )
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
        val amount = parsed?.totalAmount ?: f?.amount ?: return
        val category = container.expensesRepository.defaultCategory()
        container.pendingNotificationExpenseRepository.addPending(
            PendingNotificationExpense(
                id = System.currentTimeMillis(),
                title = parsed?.title ?: title(f?.vendor, f?.bank, category?.name),
                totalAmount = amount,
                currency = parsed?.currency ?: settings.defaultCurrency,
                vendor = parsed?.vendor ?: f?.vendor,
                category = parsed?.category ?: category?.name,
                capturedAt = System.currentTimeMillis(),
                bank = parsed?.bank ?: f?.bank,
                // The queue's own default, and the thing approving the entry confirms. A starting
                // position for the reviewer, not a claim about the message.
                direction = f?.direction ?: TransactionDirection.OUTGOING,
                templateHash = f?.templateHash
            )
        )
        Logger.d(TAG, "Queued for review: $amount")
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
}
