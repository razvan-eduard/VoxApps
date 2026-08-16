package com.voxapps.expenses.domain.llm

import com.voxapps.logging.Logger

private const val TAG = "ScanItemsReader"

/**
 * The one place a scan's line items are read deterministically.
 *
 * Three readings are tried against the same document, in order of how much of the page's structure
 * they rely on: the geometric column reconstruction first, then the text patterns
 * ([LineItemBattery]). Every one of them has to end at the same place — rows whose own arithmetic
 * holds and whose sum is a figure the document prints — so which one answered does not change how
 * far the result can be trusted, only how it was obtained.
 *
 * Callers get items or nothing. A document none of the readings can explain produces no items at
 * all, which is the outcome that matters: an empty list is a record a person can complete, while an
 * invented one is a record they have to notice is wrong first.
 */
object ScanItemsReader {

    /** @property templateId which reading answered — kept so a vendor's winner can be tried first. */
    data class Result(val items: List<TableItemsPreParse.Item>, val templateId: String)

    fun read(
        rawText: String,
        totals: ReceiptTotalRegexParser.Result,
        preferredTemplateId: String? = null
    ): Result? {
        val sections = ReceiptSections.split(rawText)
        val targets = LineItemBattery.Targets(
            invoiceTotal = totals.invoiceTotal ?: totals.total,
            // A document that separates its own charges from a carried balance also tends to print
            // the pre-tax subtotal its rows add up to; where it does not, these stay null and the
            // invoice total carries the check alone.
            netSubtotal = null,
            vatTotal = null
        )

        // The column reconstruction still reads the table its own way — it resolves which column is
        // which by arithmetic rather than by a declared order, which is stronger than any pattern
        // when the geometry survived.
        val columnar = TableItemsPreParse.parse(rawText, targets.invoiceTotal)
            ?.map { LineItemBattery.Row(it.name, it.quantity, it.unitPrice) }

        val reading = LineItemBattery.read(
            itemsText = sections.items,
            targets = targets,
            columnarRows = columnar,
            preferredTemplateId = preferredTemplateId
        ) ?: run {
            Logger.d(TAG, "No reading of this document reconciles — emitting no items")
            return null
        }

        Logger.d(
            TAG,
            "Items read by '${reading.templateId}': ${reading.rows.size} row(s) summing to " +
                "${reading.matchedTarget}, which the document prints"
        )
        return Result(
            items = reading.rows.map {
                TableItemsPreParse.Item(name = it.name, quantity = it.quantity, unitPrice = it.unitPrice)
            },
            templateId = reading.templateId
        )
    }
}
