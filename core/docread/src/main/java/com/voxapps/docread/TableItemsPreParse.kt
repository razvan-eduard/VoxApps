package com.voxapps.docread

import org.json.JSONArray
import org.json.JSONObject

/**
 * Deterministic line-item extraction from Vision's table-mode text (rows shaped
 * `description | col | col | ...` — see vox-vision's TableReconstructor), accepted ONLY when the
 * items it reads add up to the document's own printed total. That gate is what makes this pure
 * transcription rather than a guess: any misread column, merged row, or stray figure breaks the
 * sum, the parse rejects itself, and the LLM does the items as before. A win means the LLM can be
 * told not to touch the items at all — printed digits, checked against the document's own
 * arithmetic, beat any model's reading of the same text.
 */
object TableItemsPreParse {

    /**
     * @property vatAmount the tax charged on this row, where the table printed a column for it and
     *  that column proved itself — see [ColumnRoleInference]. Null where the document was silent,
     *  never derived from a rate: rounding to the cent does not distribute, so a row's share of a
     *  printed tax total is a calculation and this reads only what is on the page.
     */
    data class Item(
        val name: String,
        val quantity: Double,
        val unitPrice: Double,
        val vatAmount: Double? = null
    )

    /** Slack for one comparison — the same cent-rounding allowance [LineItemBattery] uses, and
     *  deliberately the same figure: both judge printed money against arithmetic on it. */
    private const val TOLERANCE = 0.02

    /** Mirrors vox-vision's OcrEngine.TABLE_SECTION_MARKER — the reconstruction rides appended
     *  behind it; everything before is the plain reading-order text every other consumer uses. */
    const val TABLE_SECTION_MARKER = "--- [table reconstruction] ---"

    /** The text with any appended table section removed — what prompts and regex parsers see. */
    fun plainText(rawText: String): String =
        rawText.substringBefore(TABLE_SECTION_MARKER).trimEnd()

    /**
     * The items, or null unless the value column proves itself — against the subtotal the table
     * prints for it, or failing that against [expectedTotal].
     *
     * Two properties of real scanned tables shape this. **Cells go missing**: a description long
     * enough to wrap occupies a line of its own, and the figures that belong beside it stay on the
     * line the row started on, so almost every column has gaps. A column is therefore read as
     * amounts *and empties*, never as amounts alone — the older rule, that every row must carry an
     * amount, disqualified every column of a wrapped table and gave up on documents whose value
     * column was perfect. **And the table states its own answer**: a subtotal row carries no
     * description, and the column its figure sits in is the value column, said by the document
     * rather than inferred. Matching a column's sum to that figure is the strongest evidence
     * available here, so it is tried before the search against a total read out of the foot.
     */
    fun parse(rawText: String, expectedTotal: Double?): List<Item>? {
        // Prefer the appended reconstruction; a text without one may still BE table-shaped (tests,
        // future senders), so fall back to scanning the whole input.
        val tableText = if (rawText.contains(TABLE_SECTION_MARKER)) {
            rawText.substringAfter(TABLE_SECTION_MARKER)
        } else {
            rawText
        }
        val allRows = tableText.lines()
            .filter { it.contains(" | ") }
            .map { line -> line.split(" | ").map { it.trim() } }
        // A data row with no description is a totals row: no item, but the table's own statement of
        // what its columns come to.
        val rows = allRows.filter { it.first().isNotBlank() }
        val totalsRows = allRows.filter { it.first().isBlank() }
        if (rows.size < 2) return null
        val columnCount = rows.groupingBy { it.size }.eachCount().maxBy { it.value }.key
        val usable = rows.filter { it.size == columnCount }
        if (usable.size < 2 || usable.size < rows.size) return null

        // Amounts or gaps, and enough amounts to be a column at all rather than a stray figure.
        val numericColumns = (1 until columnCount).filter { c ->
            usable.all { parseAmount(it[c]) != null || isEmptyCell(it[c]) } &&
                usable.count { parseAmount(it[c]) != null } >= MIN_COLUMN_ENTRIES
        }
        if (numericColumns.isEmpty()) return null

        fun columnSum(c: Int) = usable.sumOf { parseAmount(it[c]) ?: 0.0 }

        // What the table says a column comes to, where it says it. A totals row states the tax
        // column as readily as the value column — both sums are printed on it and both are true —
        // so where more than one column proves itself the largest is taken: on a table that
        // separates them, the values exceed the tax charged on those same values.
        val printedSubtotal = numericColumns.filter { c ->
            totalsRows.any { totals ->
                c < totals.size && parseAmount(totals[c])?.let { matches(columnSum(c), it) } == true
            }
        }.maxByOrNull { columnSum(it) }
        // Otherwise the older search: the value column alone (net- or gross-priced documents),
        // else a value+VAT pair, against the total the foot of the document gave us.
        val fromExpected = if (printedSubtotal == null && expectedTotal != null && expectedTotal > 0.0) {
            numericColumns.firstOrNull { c -> matches(columnSum(c), expectedTotal) }
                ?: numericColumns.flatMap { a -> numericColumns.map { b -> a to b } }
                    .firstOrNull { (a, b) -> a != b && matches(columnSum(a) + columnSum(b), expectedTotal) }
                    ?.first
        } else null
        val valueColumn = printedSubtotal ?: fromExpected ?: return null

        // Only a row that carries a value is an item; the rest are the wrapped remains of one.
        val itemRows = usable.filter { parseAmount(it[valueColumn]) != null }
        if (itemRows.size < 2) return null

        // Quantity x unit-price columns that reproduce the value column wherever all three are
        // present. Rows that lost one of them to wrapping simply keep the value as a unit price —
        // the sum is unaffected either way, and the sum is what the reading is judged on.
        val qtyUnit = numericColumns.flatMap { q -> numericColumns.map { u -> q to u } }
            .firstOrNull { (q, u) ->
                if (q == u || q == valueColumn || u == valueColumn) return@firstOrNull false
                val complete = itemRows.filter {
                    parseAmount(it[q]) != null && parseAmount(it[u]) != null
                }
                complete.size >= MIN_COLUMN_ENTRIES && complete.all { row ->
                    matches(parseAmount(row[q])!! * parseAmount(row[u])!!, parseAmount(row[valueColumn])!!)
                }
            }
        // Which column holds the tax, decided by what a tax column does rather than by its heading:
        // it is a constant fraction of the values it is charged on. See [ColumnRoleInference].
        val roles = ColumnRoleInference.infer(
            columns = (0 until columnCount).map { c -> itemRows.map { parseAmount(it[c]) } },
            printedTotals = totalsRows.flatMap { totals ->
                totals.indices.mapNotNull { parseAmount(totals[it]) }
            }
        )
        val vatColumn = roles.vat?.takeIf { it != valueColumn }

        return itemRows.map { row ->
            val value = parseAmount(row[valueColumn])!!
            val qty = qtyUnit?.let { parseAmount(row[it.first]) }
            val unit = qtyUnit?.let { parseAmount(row[it.second]) }
            val vat = vatColumn?.let { parseAmount(row[it]) }
            if (qty != null && unit != null) Item(row[0], qty, unit, vat) else Item(row[0], 1.0, value, vat)
        }
    }

    private fun isEmptyCell(cell: String) = cell == "-" || cell.isBlank()

    /** Below this a column is a stray figure or two, not a column the sum can be trusted from. */
    private const val MIN_COLUMN_ENTRIES = 2

    private fun matches(a: Double, b: Double) = kotlin.math.abs(a - b) <= TOLERANCE

    private fun parseAmount(cell: String): Double? {
        if (cell == "-" || cell.isBlank()) return null
        val normalized = cell.replace(" ", "").let {
            if (it.count { ch -> ch == ',' } == 1 && !it.contains('.')) it.replace(',', '.') else it.replace(",", "")
        }
        return normalized.toDoubleOrNull()
    }

    fun toJson(items: List<Item>): String {
        val array = JSONArray()
        items.forEach { item ->
            val o = JSONObject().put("n", item.name).put("q", item.quantity).put("u", item.unitPrice)
            // Written only where the document printed it, so an item with no tax column stays the
            // same three keys it always was and an older reader loses nothing it understood.
            item.vatAmount?.let { o.put("v", it) }
            array.put(o)
        }
        return array.toString()
    }

    fun fromJson(json: String?): List<Item>? = try {
        if (json.isNullOrBlank()) null else {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                Item(
                    o.optString("n"),
                    o.optDouble("q", 1.0),
                    o.optDouble("u", 0.0),
                    if (o.has("v")) o.optDouble("v") else null
                )
            }.takeIf { it.isNotEmpty() }
        }
    } catch (e: Exception) {
        null
    }
}
