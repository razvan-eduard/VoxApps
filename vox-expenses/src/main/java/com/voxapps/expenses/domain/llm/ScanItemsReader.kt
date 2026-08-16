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
            vatTotal = null,
            // Every amount printed in the foot, labelled or not. A subtotal is routinely printed
            // above its totals block with the caption to one side, and OCR loses captions far more
            // readily than it loses figures — a real invoice arrived with "18.36" intact and the
            // words beside it gone. Admitting the bare figures costs nothing, because a candidate
            // still has to reconstruct every row's own amount and sum to one of them exactly.
            labelledOther = footerAmounts(sections.footer)
        )

        // The column reconstruction still reads the table its own way — it resolves which column is
        // which by arithmetic rather than by a declared order, which is stronger than any pattern
        // when the geometry survived.
        val columnar = TableItemsPreParse.parse(rawText, targets.invoiceTotal)
            ?.map { LineItemBattery.Row(it.name, it.quantity, it.unitPrice) }

        // Two bodies of text are worth reading, and which one carries the rows varies by scan.
        //
        // The items section holds the column reconstruction, whose cells are separated by " | " and
        // whose gaps are "-" — a shape the patterns cannot match, so it is flattened back to plain
        // spacing for them. The plain reading-order text is the other: it sits before the markers,
        // belongs to no section, and on a well-photographed page it already reads
        // "… pers 2 2.35 4.70 0.99", exactly what the strict pattern wants. A reconstruction that
        // fragmented a row can leave the plain text the better of the two, so both are offered and
        // the arithmetic decides.
        val candidates = listOf(flattenColumns(sections.items), sections.plain)
            .filter { it.isNotBlank() }

        val reading = candidates.firstNotNullOfOrNull { text ->
            LineItemBattery.read(
                itemsText = text,
                targets = targets,
                columnarRows = columnar,
                preferredTemplateId = preferredTemplateId
            )
        } ?: run {
            Logger.d(
                TAG,
                "No reading of this document reconciles — emitting no items " +
                    "(targets: ${targets.accepted().joinToString()})"
            )
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

    /**
     * Every amount printed in the document's foot.
     *
     * Deliberately unfussy: a figure only becomes a target by having a correct-looking item list sum
     * to it exactly, so there is no need to decide here which of them is a total. Bounded so a long
     * footer cannot turn into a lottery of candidates.
     */
    private fun footerAmounts(footer: String): List<Double> =
        AMOUNT.findAll(footer)
            .mapNotNull { com.voxapps.textmatch.extract.AmountText.normalize(it.value) }
            .filter { it > 0.0 }
            .distinct()
            .take(MAX_FOOTER_CANDIDATES)
            .toList()

    /**
     * The reconstruction's rows as ordinary spaced text: cell separators become spaces and empty
     * cells disappear.
     *
     * The columns are still doing their work — this is the reconstruction's own row grouping, which
     * is what makes each line one printed row — but the patterns read words and numbers, not a
     * table format, so the punctuation that carried the structure is removed before they see it.
     */
    private fun flattenColumns(itemsSection: String): String =
        itemsSection.lines()
            .joinToString("\n") { line ->
                line.split(" | ")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && it != "-" }
                    .joinToString(" ")
            }
            .trim()

    private val AMOUNT = Regex("""\d{1,3}(?:[ .,]\d{3})*[.,]\d{1,2}|\d+[.,]\d{1,2}""")

    /** A foot holds a handful of figures; anything past that is not a totals block. */
    private const val MAX_FOOTER_CANDIDATES = 12
}
