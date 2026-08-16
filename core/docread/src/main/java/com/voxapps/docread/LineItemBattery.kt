package com.voxapps.docread

import com.voxapps.textmatch.extract.AmountText
import kotlin.math.abs

/**
 * Reads line items by trying every way it knows and keeping the one the document's own arithmetic
 * proves.
 *
 * No template is trusted for looking plausible. A candidate is accepted only when both hold:
 *
 *  1. every row reconstructs its own printed amount — `quantity × unitPrice = value`; and
 *  2. the rows together sum to a figure the document actually prints — the invoice's own total, its
 *     net subtotal, or that subtotal plus its VAT ([InvoiceTotalsReconciler.itemsBelong]).
 *
 * That is what makes trying many candidates safe rather than reckless. A template written for
 * someone else's receipt cannot damage this one: it either reconciles, in which case it read the
 * document correctly, or it loses and nothing is emitted. The cost of a wrong template is coverage,
 * never a wrong record — which is also why the loosest patterns are ordered last, where they can
 * only win on documents no stricter pattern could read.
 *
 * Matching is to the cent. With a list of candidates, a percentage tolerance would eventually crown
 * a wrong reading by luck — twelve rows of a real invoice sum to its printed subtotal exactly, so
 * there is no reason to accept less.
 */
object LineItemBattery {

    /** Slack for one comparison: printed figures are rounded to the cent and rows accumulate that. */
    private const val TOLERANCE = 0.02

    data class Row(val name: String, val quantity: Double, val unitPrice: Double) {
        val value: Double get() = quantity * unitPrice
    }

    /**
     * @property templateId which candidate read the document — recorded so a vendor's winning
     *  template can be tried first next time.
     * @property matchedTarget the printed figure the rows summed to, i.e. what proved them.
     */
    data class Reading(val templateId: String, val rows: List<Row>, val matchedTarget: Double)

    /**
     * The figures a correct item list is allowed to sum to.
     *
     * [labelledOther] are amounts printed in the document's foot that carry no label this app
     * recognises — a subtotal above a totals block, a figure whose caption was lost to OCR. They are
     * admitted as candidates because the check they face is not "is this number plausible" but "do
     * twelve rows, each reconstructing its own printed amount, sum to it exactly" — which a number
     * that means something else does not survive. Requiring a caption instead would throw away
     * correct readings for want of a word: a real invoice printed its subtotal, its rows added up to
     * it precisely, and the only thing missing was the label beside it.
     */
    data class Targets(
        val invoiceTotal: Double?,
        val netSubtotal: Double? = null,
        val vatTotal: Double? = null,
        val labelledOther: List<Double> = emptyList()
    ) {
        internal fun accepted(): List<Double> =
            InvoiceTotalsReconciler.acceptedTargets(invoiceTotal, netSubtotal, vatTotal) + labelledOther
    }

    /**
     * @property continuation lines that carry no numbers and belong to the description of a row —
     *  matched *before* the row they precede, the way a wrapped description is printed above the
     *  line holding its amounts.
     */
    data class Template(
        val id: String,
        val row: Regex,
        val continuation: Regex? = null
    )

    /**
     * The patterns, strictest first.
     *
     * Order is the only priority mechanism: the first candidate that reconciles wins, so a pattern
     * that constrains more — one demanding a quantity, a unit price and a value — gets to answer
     * before one that would match almost any line with a number at the end.
     */
    val BUILT_IN: List<Template> = listOf(
        // Row ends in quantity, unit price, value (and possibly VAT), with the description before
        // them; unnumbered lines above continue that description. This is the shape of an invoice
        // whose service descriptions wrap over several printed lines.
        Template(
            id = "numeric-tail",
            row = Regex("""^(?<desc>.*?\S)\s+(?<qty>-?[\d.,]+)\s+(?<unit>-?[\d.,]+)\s+(?<value>-?[\d.,]+)(?:\s+-?[\d.,]+)?\s*$"""),
            continuation = Regex("""^\s*(?<desc>[^\d\s][^\d]*)$""")
        ),
        // "Paine integrala 500g 1 buc x 6,50 RON ..... 6,50 RON TVA A" — a fiscal receipt line, where
        // the unit of measure, the currency and the dotted filler are all optional noise.
        Template(
            id = "qty-unit-x-price-value",
            row = Regex(
                """^(?<desc>.+?)\s+(?<qty>[\d.,]+)\s*[a-zA-Z]{0,4}\s*[xX*]\s*(?<unit>[\d.,]+)\s*[a-zA-Z]{0,4}\s*\.*\s*(?<value>[\d.,]+)\s*[a-zA-Z]{0,4}\s*$"""
            )
        ),
        // The same without a separate line total: quantity times price IS the amount.
        Template(
            id = "qty-x-price",
            row = Regex("""^(?<desc>.+?)\s+(?<qty>[\d.,]+)\s*[xX*]\s*(?<unit>[\d.,]+)\s*$""")
        ),
        // Quantity first, then the name, then one amount — tills and restaurant bills.
        Template(
            id = "qty-first",
            row = Regex("""^(?<qty>\d{1,3})\s+(?<desc>\D.*?)\s+(?<value>[\d.,]+)\s*$""")
        ),
        // Weakest on purpose, and therefore last: a name and one amount. It matches nearly any line,
        // so it only ever survives on a document where the sum reconciles exactly.
        Template(
            id = "name-amount",
            row = Regex("""^(?<desc>[^\d\s].*?)\s+(?<value>[\d.,]+)\s*$""")
        )
    )

    /**
     * The reading the document proves, or null when nothing does.
     *
     * [columnarRows] is the geometric reconstruction's own answer (see [TableItemsPreParse]) — it is
     * tried first when present, because columns recovered from the page's geometry beat any guess
     * made from flattened text. [preferredTemplateId] is a hint, not a decision: it is tried before
     * the rest and, failing to reconcile, simply falls back to the full run.
     */
    fun read(
        itemsText: String,
        targets: Targets,
        columnarRows: List<Row>? = null,
        preferredTemplateId: String? = null,
        templates: List<Template> = BUILT_IN
    ): Reading? {
        if (targets.accepted().isEmpty()) return null

        columnarRows?.let { rows ->
            proven(rows, targets)?.let { return Reading("columnar", rows, it) }
        }

        val ordered = preferredTemplateId
            ?.let { id -> templates.filter { it.id == id } + templates.filter { it.id != id } }
            ?: templates

        for (template in ordered) {
            val rows = applyTemplate(itemsText, template)
            if (rows.size < MIN_ROWS) continue
            proven(rows, targets)?.let { return Reading(template.id, rows, it) }
        }
        return null
    }

    /** A single row proves nothing — one line matching one pattern is a coincidence, not a table. */
    private const val MIN_ROWS = 2

    /** The printed figure these rows sum to, or null if they sum to none of them. */
    private fun proven(rows: List<Row>, targets: Targets): Double? {
        if (rows.isEmpty()) return null
        val sum = rows.sumOf { it.value }
        return targets.accepted().firstOrNull { abs(sum - it) <= TOLERANCE }
    }

    private fun applyTemplate(itemsText: String, template: Template): List<Row> {
        val rows = mutableListOf<Row>()
        val pendingDescription = StringBuilder()

        for (line in itemsText.lines()) {
            if (line.isBlank()) continue

            val match = template.row.find(line)
            if (match == null) {
                // Only a template that says descriptions wrap keeps unmatched lines; for every other
                // one an unmatched line is simply not a row.
                template.continuation?.find(line)?.let { cont ->
                    val text = cont.groupOrNull("desc")?.trim().orEmpty()
                    if (text.isNotEmpty()) {
                        if (pendingDescription.isNotEmpty()) pendingDescription.append(' ')
                        pendingDescription.append(text)
                    }
                }
                continue
            }

            val qty = match.groupOrNull("qty")?.let { AmountText.normalize(it) } ?: 1.0
            val unit = match.groupOrNull("unit")?.let { AmountText.normalize(it) }
            val value = match.groupOrNull("value")?.let { AmountText.normalize(it) }

            // Whatever two of the three are printed, the third is arithmetic. A row that cannot be
            // completed — or whose printed figures contradict each other — is not read at all,
            // which is what stops a pattern from matching a line it does not understand.
            val unitPrice = when {
                unit != null && value != null -> {
                    if (qty <= 0.0 || abs(qty * unit - value) > TOLERANCE) return emptyList()
                    unit
                }
                unit != null -> unit
                value != null && qty > 0.0 -> value / qty
                else -> return emptyList()
            }
            if (unitPrice <= 0.0 || qty <= 0.0) return emptyList()

            val own = match.groupOrNull("desc")?.trim().orEmpty()
            val name = listOf(pendingDescription.toString().trim(), own)
                .filter { it.isNotEmpty() }
                .joinToString(" ")
            pendingDescription.setLength(0)
            if (name.isEmpty()) return emptyList()

            rows += Row(name = name, quantity = qty, unitPrice = unitPrice)
        }
        return rows
    }

    /** Named groups a pattern may or may not declare — asking for an absent one must not throw. */
    private fun MatchResult.groupOrNull(name: String): String? =
        runCatching { groups[name]?.value }.getOrNull()?.takeIf { it.isNotBlank() }
}
