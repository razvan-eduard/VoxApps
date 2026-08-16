package com.voxapps.expenses.domain.llm

import android.content.Context
import com.voxapps.docread.ScanReading
import com.voxapps.docread.InvoiceTotalsReconciler
import com.voxapps.docread.TaxBreakdown
import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.expenses.di.ExpensesContainer
import com.voxapps.logging.Logger

private const val TAG = "ModelFreeScan"

/**
 * Turns a scan into an expense without asking anything of a model.
 *
 * The deterministic reader already produces what a record is mostly made of: the amount, the three
 * totals, the line items, and — with no arithmetic behind it — the vendor and date off the
 * letterhead. What it cannot produce is a judgement. Nothing on a page says which category a
 * purchase belongs to, so nothing here guesses: the fallback category takes it, the one the user
 * starred, and the title is written from what is known rather than invented.
 *
 * That is the whole bargain of the setting this serves. A record arrives complete in its figures and
 * plain in its wording, and nothing about it left the device. Where the figures could not be proved,
 * the fields are simply empty — a blank a person fills, which is the same trade every other part of
 * this reader makes.
 */
object ModelFreeScanCreator {

    /**
     * @return the new expense's id, or null when the scan yielded no amount at all — there is no
     *  record to write from a page whose total could not be read, and inventing one would be worse
     *  than the scan visibly doing nothing.
     */
    suspend fun create(
        context: Context,
        container: ExpensesContainer,
        reading: ScanReading.Result,
        plainText: String,
        imageName: String?
    ): Long? {
        val settings = container.settingsRepository.getSnapshot()
        val total = reading.totals.total ?: reading.totals.invoiceTotal
        if (total == null || total <= 0.0) {
            Logger.d(TAG, "No total could be read — no expense created, nothing sent anywhere")
            return null
        }

        // What the rows come to, checked against what the document says they come to. A figure the
        // page stated only once is usable but unvouched for; one the restatements contradict is
        // reported rather than stored as fact. See [TaxBreakdown].
        val items = reading.items.orEmpty()
        val breakdown = TaxBreakdown.resolve(
            itemNets = items.map { it.quantity * it.unitPrice },
            itemVats = items.map { it.vatAmount },
            printedGross = reading.totals.invoiceTotal ?: reading.totals.total
        )

        val category = container.expensesRepository.defaultCategory()
        val vendor = reading.header.vendor
        val dateTime = DateTimeRegexParser.parse(plainText)

        val parsed = ExpenseParseResultParser.Parsed(
            title = title(vendor, category?.name),
            totalAmount = total,
            currency = settings.defaultCurrency,
            vendor = vendor,
            bank = null,
            // A printed address is the model's reading of the page, and there is no model here; the
            // location fill falls through to whatever the settings allow, as it does for voice.
            location = null,
            category = category?.name,
            date = reading.header.date ?: dateTime.date,
            time = dateTime.time,
            // Written only where the table printed a tax column that proved itself; a row the
            // document was silent about stays silent here, rather than carrying a share of a total
            // that rounding would not distribute exactly.
            items = items.map {
                ExpenseParseResultParser.ParsedItem(
                    name = it.name,
                    quantity = it.quantity,
                    unitPrice = it.unitPrice,
                    netAmount = it.vatAmount?.let { _ -> it.quantity * it.unitPrice },
                    vatAmount = it.vatAmount,
                    grossAmount = it.vatAmount?.let { vat -> it.quantity * it.unitPrice + vat }
                )
            }
        )

        Logger.d(
            TAG,
            "Created without a model: total $total, ${parsed.items.size} item(s), " +
                "vendor ${vendor ?: "—"}, category ${category?.name ?: "—"}; " +
                "net ${breakdown.net ?: "—"}, tax ${breakdown.vat ?: "—"} (${breakdown.verdict})"
        )
        val newId = com.voxapps.expenses.receiver.LlmResultReceiver().createExpenseFromParsed(
            appContext = context.applicationContext,
            container = container,
            parsed = parsed,
            imageName = imageName,
            preParse = null
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
     * `Vendor Category`, or whichever half is known.
     *
     * Deliberately not a sentence. The title exists to be recognised in a list, and two words that
     * are both true beat a fuller phrase that had to be guessed at — which is the only thing a model
     * would add here.
     */
    private fun title(vendor: String?, category: String?): String? =
        listOfNotNull(vendor?.takeIf { it.isNotBlank() }, category?.takeIf { it.isNotBlank() })
            .joinToString(" ")
            .takeIf { it.isNotBlank() }

    /** Whether this scan should skip the model entirely. */
    fun isEnabled(settings: ExpensesSettings): Boolean =
        settings.scanModelUse == ExpensesSettings.SCAN_MODEL_NONE
}
