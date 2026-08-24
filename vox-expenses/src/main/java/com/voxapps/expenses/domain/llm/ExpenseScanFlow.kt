package com.voxapps.expenses.domain.llm

import android.content.Context
import com.voxapps.docread.ScanReading
import com.voxapps.docread.InvoiceTotalsReconciler
import com.voxapps.docread.TaxBreakdown
import com.voxapps.expenses.data.ExpenseSuggestionTarget
import com.voxapps.expenses.data.PendingLineItemsJson
import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.expenses.di.ExpensesContainer
import com.voxapps.logging.Logger
import com.voxapps.recordflow.AskScope
import com.voxapps.expenses.data.preferences.knownCurrencies
import com.voxapps.expenses.data.ExpenseOrigins
import com.voxapps.recordflow.FieldOrigin
import com.voxapps.recordflow.DeterministicReading
import com.voxapps.recordflow.FieldWeight
import com.voxapps.recordflow.FlowSupport
import com.voxapps.recordflow.RecordFlowSpec
import com.voxapps.recordflow.RecordSource
import kotlinx.coroutines.flow.first

private const val TAG = "ExpenseScanFlow"

/** A scanned page as it arrives: the reconstruction for the deterministic reader, and the plain
 *  reading-order text everything else sees. */
data class ScannedPage(val rawText: String, val plainText: String)

/** What the page proved, kept with the text it was proved from — both halves are needed to write the
 *  record, and neither survives the round trip on its own. */
data class ProvedScan(val reading: ScanReading.Result, val plainText: String)

/**
 * A scanned page becoming an expense — the widest of the flows, and the only one whose record has a
 * body worth arguing about.
 *
 * The head is the vendor, the title, the category: coarse, and wrong in a way anyone spots. The body
 * is the line items, where a row reading 51,38 for 51,33 is indistinguishable from a right one and
 * nothing downstream catches it. That split is what the middle rungs exist for, and this is the one
 * satellite that can honour them, because it has somewhere to hold a proposal.
 *
 * What makes the offline rung real here is arithmetic rather than trust: [ScanReading] accepts a set
 * of rows only when they sum to a figure the document prints, so what the device establishes it has
 * also proved. A model, asked, answers only what no arithmetic could settle.
 */
class ExpenseScanFlow(
    private val context: Context,
    private val container: ExpensesContainer,
    /** Per capture, and needed in both halves: the reading is gone by the time a reply lands, and
     *  the reply never knew about the photograph. */
    private val imageName: String? = null,
    /**
     * What the page proved before it was asked about, kept aside against the request id so it
     * survives the round trip — see [ScanPreParseRepository]. Null while dispatching, because the
     * reading itself is still in hand; present at delivery, where it is the only form of it left.
     */
    private val suppressed: ScanPreParse? = null
) : RecordFlowSpec<ScannedPage, ProvedScan, ExpenseParseResultParser.Parsed> {

    override val source = RecordSource.SCAN
    override val support: FlowSupport = ExpensesSettings.SCAN_FLOW_SUPPORT
    override val taskId = LlmTasks.EXPENSE_SCAN_CLEANUP

    override suspend fun read(input: ScannedPage): DeterministicReading<ProvedScan> {
        val reading = ExpenseScanCleanupRequestSender.readScanFor(context, input.rawText, input.plainText)
        val total = reading.totals.total ?: reading.totals.invoiceTotal
        val hasTotal = total != null && total > 0.0
        return DeterministicReading(
            fields = ProvedScan(reading, input.plainText),
            // Without an amount there is no expense to write, and inventing one would be worse than
            // the scan visibly doing nothing.
            usable = hasTotal,
            // The coarse fields, since those are what a model would be asked for. A page whose
            // letterhead named its vendor has nothing left that no arithmetic could settle — the
            // category falls back to the starred one, exactly as it does for a voice entry.
            complete = hasTotal && !reading.header.vendor.isNullOrBlank()
        )
    }

    override suspend fun prompt(reading: DeterministicReading<ProvedScan>, asks: AskScope): String {
        val settings = container.settingsRepository.getSnapshot()
        val categories = container.expensesRepository.categories.first().map { it.name }
        val plainText = reading.fields.plainText
        val dateTime = DateTimeRegexParser.parse(plainText)
        return ExpenseScanCleanupPromptBuilder.build(
            plainText,
            categories,
            settings.defaultCurrency,
            settings.language,
            preParsedDate = dateTime.date,
            preParsedTime = dateTime.time,
            // Two independent reasons to leave the item half out, and either is enough: this rung
            // never asks about the fine detail, or the engine cannot take a prompt that long.
            includeLineItems = asks.covers(FieldWeight.BODY) &&
                askEngineForLineItems(context)
        )
    }

    /**
     * The reply, reunited with what was suppressed from the question. A field the prompt told the
     * model to skip is absent by design rather than missing, and the deterministic value outranks
     * anything a model would have produced for it — it was read from the page's own characters.
     */
    override suspend fun parse(reply: String): ExpenseParseResultParser.Parsed? =
        with(com.voxapps.expenses.receiver.LlmResultReceiver()) {
            ExpenseParseResultParser
                .parse(reply, requireTotalAmount = suppressed?.total == null)
                ?.withPreParse(suppressed)
                ?.withoutDisprovedItems(suppressed)
        }

    override suspend fun commit(
        reading: DeterministicReading<ProvedScan>?,
        parsed: ExpenseParseResultParser.Parsed?,
        applies: (FieldWeight) -> Boolean
    ): Long? {
        val proved = reading?.fields
            // A reply with no reading in hand: what the page proved is in the store instead, and
            // the rung governs the answer just the same.
            ?: return writeFromReply(parsed ?: return null, applies)
        // What the device proved is written; what a model answered is written only where this rung
        // says so, and offered where it does not.
        val head = parsed?.takeIf { applies(FieldWeight.HEAD) }
        val body = parsed?.takeIf { applies(FieldWeight.BODY) }

        val recordId = writeRecord(proved, head, body?.items) ?: return null
        offerWhatWasNotWritten(recordId, parsed, applies)
        return recordId
    }

    /**
     * The record itself.
     *
     * Everything the page proved goes on it — the amount, the three totals, the rows that summed to
     * one of them — because it was established here rather than proposed by anyone. [head] and
     * [items] are what a model answered *and* this rung agreed to write; where they are absent the
     * fields fall back to what the page said, or to the starred category, and the title is composed
     * from what is known rather than invented.
     */
    private suspend fun writeRecord(
        proved: ProvedScan,
        head: ExpenseParseResultParser.Parsed?,
        items: List<ExpenseParseResultParser.ParsedItem>?
    ): Long? {
        val reading = proved.reading
        val settings = container.settingsRepository.getSnapshot()
        val total = head?.totalAmount ?: reading.totals.total ?: reading.totals.invoiceTotal
        if (total == null || total <= 0.0) {
            Logger.d(TAG, "No total could be read — no expense created, nothing sent anywhere")
            return null
        }

        val provedItems = reading.items.orEmpty()
        // What the rows come to, checked against what the document says they come to. A figure the
        // page stated only once is usable but unvouched for; one the restatements contradict is
        // reported rather than stored as fact.
        val breakdown = TaxBreakdown.resolve(
            itemNets = provedItems.map { it.quantity * it.unitPrice },
            itemVats = provedItems.map { it.vatAmount },
            printedGross = reading.totals.invoiceTotal ?: reading.totals.total
        )

        val category = container.expensesRepository.defaultCategory()
        // What settles a currency spelling that names several — see CurrencyCodes.
        val accountCurrencies = container.expensesRepository.bankAccounts.first().map { it.currencyCode }
        val vendor = head?.vendor ?: reading.header.vendor
        val dateTime = DateTimeRegexParser.parse(proved.plainText)

        val record = ExpenseParseResultParser.Parsed(
            title = head?.title ?: composeTitle(vendor, category?.name),
            totalAmount = total,
            // The page says what its figures are figures of, as plainly as it says the figures —
            // so it is read rather than asked for or defaulted. Exactly one currency or none: a
            // receipt printing a second one is converting, and choosing between them is not a
            // reading.
            currency = com.voxapps.textmatch.extract.CurrencyCodes.find(
                proved.plainText,
                settings.knownCurrencies(accountCurrencies)
            ) ?: head?.currency ?: settings.defaultCurrency,
            vendor = vendor,
            bank = head?.bank,
            // A printed address is a reading of the page; with no model there is none, and the
            // location fill falls through to whatever the settings allow, as it does for voice.
            location = head?.location,
            category = head?.category ?: category?.name,
            date = head?.date ?: reading.header.date ?: dateTime.date,
            time = head?.time ?: dateTime.time,
            items = items ?: provedItems.map {
                ExpenseParseResultParser.ParsedItem(
                    name = it.name,
                    quantity = it.quantity,
                    unitPrice = it.unitPrice,
                    // Written only where the table printed a tax column that proved itself; a row
                    // the document was silent about stays silent here rather than carrying a share
                    // of a total that rounding would not distribute exactly.
                    netAmount = it.vatAmount?.let { _ -> it.quantity * it.unitPrice },
                    vatAmount = it.vatAmount,
                    grossAmount = it.vatAmount?.let { vat -> it.quantity * it.unitPrice + vat }
                )
            }
        )

        Logger.d(
            TAG,
            "Writing scan: total $total, ${record.items.size} item(s), vendor ${vendor ?: "—"}, " +
                "answered head=${head != null} items=${items != null}; " +
                "net ${breakdown.net ?: "—"}, tax ${breakdown.vat ?: "—"} (${breakdown.verdict})"
        )
        // Which half of the record each field came from — the page's own characters and arithmetic,
        // or a sentence a model wrote. The choice was made field by field above; this only records
        // the choices that were already taken.
        val answered = buildSet {
            if (head?.title != null) add(ExpenseOrigins.FIELD_TITLE)
            if (head?.vendor != null) add(ExpenseOrigins.FIELD_VENDOR)
            if (head?.bank != null) add(ExpenseOrigins.FIELD_BANK)
            if (head?.location != null) add(ExpenseOrigins.FIELD_LOCATION)
            if (head?.category != null) add(ExpenseOrigins.FIELD_CATEGORY)
            if (head?.currency != null && record.currency == head.currency) add(ExpenseOrigins.FIELD_CURRENCY)
            if (head?.date != null) add(ExpenseOrigins.FIELD_DATE)
            if (items != null) add(ExpenseOrigins.FIELD_ITEMS)
        }
        val provedFields = buildSet {
            add(ExpenseOrigins.FIELD_AMOUNT)
            if (ExpenseOrigins.FIELD_CURRENCY !in answered) add(ExpenseOrigins.FIELD_CURRENCY)
            if (ExpenseOrigins.FIELD_VENDOR !in answered && vendor != null) add(ExpenseOrigins.FIELD_VENDOR)
            if (ExpenseOrigins.FIELD_DATE !in answered && record.date != null) add(ExpenseOrigins.FIELD_DATE)
            if (ExpenseOrigins.FIELD_ITEMS !in answered && record.items.isNotEmpty()) add(ExpenseOrigins.FIELD_ITEMS)
        }
        val newId = com.voxapps.expenses.receiver.LlmResultReceiver().createExpenseFromParsed(
            appContext = context.applicationContext,
            container = container,
            parsed = record,
            imageName = imageName,
            preParse = null,
            sourceText = proved?.plainText,
            origins = mapOf(
                FieldOrigin.ANSWERED to answered,
                FieldOrigin.PROVED to provedFields
            )
        )

        // The two figures the creation path has no field for. Written only where the reading
        // produced them, and never where the restatements disagreed — a contradicted breakdown is
        // one of the readings being wrong, and storing it would put the wrong one on the record.
        if (breakdown.verdict != InvoiceTotalsReconciler.Verdict.CONTRADICTED &&
            (breakdown.net != null || breakdown.vat != null)
        ) {
            val stored = container.expensesRepository.getExpenseById(newId)
            if (stored != null) {
                container.expensesRepository.updateExpense(
                    stored.expense.copy(netAmount = breakdown.net, vatAmount = breakdown.vat),
                    stored.items
                )
            }
        }
        return newId
    }

    /**
     * `Vendor Category`, or whichever half is known. Deliberately not a sentence: the title exists
     * to be recognised in a list, and two words that are both true beat a fuller phrase that had to
     * be guessed at — which is the only thing a model would add here.
     */
    private fun composeTitle(vendor: String?, category: String?): String? =
        listOfNotNull(vendor?.takeIf { it.isNotBlank() }, category?.takeIf { it.isNotBlank() })
            .joinToString(" ")
            .takeIf { it.isNotBlank() }

    /**
     * This app's review surface is the record itself: an expense with what was proved on it, sitting
     * in the list where it will be seen and edited. So a reading that could not be finished is still
     * written — with nothing a model said applied to it, since nothing was accepted.
     */
    override suspend fun queueForReview(
        reading: DeterministicReading<ProvedScan>?,
        parsed: ExpenseParseResultParser.Parsed?
    ) {
        commit(reading, parsed) { false }
    }

    /**
     * The delivery half's write step.
     *
     * The record is built from whichever side this rung trusts for each half: the model's answer
     * where it applies, and otherwise what the page proved, recovered from [suppressed]. Both are
     * available here, which is what lets "send everything, write nothing" mean what it says instead
     * of quietly writing the answer anyway.
     */
    private suspend fun writeFromReply(
        parsed: ExpenseParseResultParser.Parsed,
        applies: (FieldWeight) -> Boolean
    ): Long? {
        val settings = container.settingsRepository.getSnapshot()
        val category = container.expensesRepository.defaultCategory()
        val headApplied = applies(FieldWeight.HEAD)
        val provedVendor = suppressed?.vendor
        val provedItems = PendingLineItemsJson.decode(suppressed?.itemsJson)

        val record = parsed.copy(
            title = if (headApplied) parsed.title else composeTitle(provedVendor, category?.name),
            vendor = if (headApplied) parsed.vendor else provedVendor,
            bank = if (headApplied) parsed.bank else null,
            category = if (headApplied) parsed.category else category?.name,
            location = if (headApplied) parsed.location else null,
            items = if (applies(FieldWeight.BODY)) parsed.items else provedItems
        )
        val newId = com.voxapps.expenses.receiver.LlmResultReceiver().createExpenseFromParsed(
            appContext = context.applicationContext,
            container = container,
            parsed = record,
            imageName = imageName,
            preParse = suppressed,
            // Settled on the way out and carried through the round trip, like the vendor and the
            // direction — the reply itself never carried the page's characters.
            knownAccountId = suppressed?.bankAccountId,
            origins = mapOf(
                FieldOrigin.ANSWERED to buildSet {
                    if (headApplied) {
                        if (parsed.title != null) add(ExpenseOrigins.FIELD_TITLE)
                        if (parsed.vendor != null) add(ExpenseOrigins.FIELD_VENDOR)
                        if (parsed.bank != null) add(ExpenseOrigins.FIELD_BANK)
                        if (parsed.location != null) add(ExpenseOrigins.FIELD_LOCATION)
                        if (parsed.category != null) add(ExpenseOrigins.FIELD_CATEGORY)
                    }
                    if (applies(FieldWeight.BODY) && parsed.items.isNotEmpty()) add(ExpenseOrigins.FIELD_ITEMS)
                },
                FieldOrigin.PROVED to buildSet {
                    add(ExpenseOrigins.FIELD_AMOUNT)
                    if (!headApplied && provedVendor != null) add(ExpenseOrigins.FIELD_VENDOR)
                    if (!applies(FieldWeight.BODY) && provedItems.isNotEmpty()) add(ExpenseOrigins.FIELD_ITEMS)
                }
            )
        )
        Logger.d(TAG, "Wrote a reply-backed record $newId (head applied=$headApplied)")
        offerWhatWasNotWritten(newId, parsed, applies)
        return newId.takeIf { it > 0 }
    }

    /** Whatever the rung declined to write is offered on the record instead, field by field. */
    private suspend fun offerWhatWasNotWritten(
        recordId: Long,
        parsed: ExpenseParseResultParser.Parsed?,
        applies: (FieldWeight) -> Boolean
    ) {
        if (parsed == null) return
        val offerHead = !applies(FieldWeight.HEAD)
        val offerBody = !applies(FieldWeight.BODY) && parsed.items.isNotEmpty()
        if (!offerHead && !offerBody) return

        container.suggestionStore.offer(
            recordId,
            mapOf(
                ExpenseSuggestionTarget.KEY_TITLE to parsed.title.takeIf { offerHead },
                ExpenseSuggestionTarget.KEY_VENDOR to parsed.vendor.takeIf { offerHead },
                ExpenseSuggestionTarget.KEY_BANK to parsed.bank.takeIf { offerHead },
                ExpenseSuggestionTarget.KEY_CATEGORY to parsed.category.takeIf { offerHead },
                ExpenseSuggestionTarget.KEY_LOCATION to parsed.location.takeIf { offerHead },
                ExpenseSuggestionTarget.KEY_ITEMS to if (offerBody) {
                    PendingLineItemsJson.encode(parsed.items)
                } else {
                    null
                }
            ).filterValues { it != null }
        )
    }

}
