package com.voxapps.expenses.domain.llm

import android.content.Context
import com.voxapps.docread.ScanReading
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
            items = reading.items.orEmpty().map {
                ExpenseParseResultParser.ParsedItem(
                    name = it.name,
                    quantity = it.quantity,
                    unitPrice = it.unitPrice
                )
            }
        )

        Logger.d(
            TAG,
            "Created without a model: total $total, ${parsed.items.size} item(s), " +
                "vendor ${vendor ?: "—"}, category ${category?.name ?: "—"}"
        )
        return com.voxapps.expenses.receiver.LlmResultReceiver().createExpenseFromParsed(
            appContext = context.applicationContext,
            container = container,
            parsed = parsed,
            imageName = imageName,
            preParse = null
        )
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
