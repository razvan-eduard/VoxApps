package com.voxapps.expenses.domain.llm

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

    data class Item(val name: String, val quantity: Double, val unitPrice: Double)

    private const val TOLERANCE = 0.05

    /** Mirrors vox-vision's OcrEngine.TABLE_SECTION_MARKER — the reconstruction rides appended
     *  behind it; everything before is the plain reading-order text every other consumer uses. */
    const val TABLE_SECTION_MARKER = "--- [table reconstruction] ---"

    /** The text with any appended table section removed — what prompts and regex parsers see. */
    fun plainText(rawText: String): String =
        rawText.substringBefore(TABLE_SECTION_MARKER).trimEnd()

    /** Null unless a column combination sums to [expectedTotal] within [TOLERANCE]. */
    fun parse(rawText: String, expectedTotal: Double?): List<Item>? {
        if (expectedTotal == null || expectedTotal <= 0.0) return null
        // Prefer the appended reconstruction; a text without one may still BE table-shaped (tests,
        // future senders), so fall back to scanning the whole input.
        val tableText = if (rawText.contains(TABLE_SECTION_MARKER)) {
            rawText.substringAfter(TABLE_SECTION_MARKER)
        } else {
            rawText
        }
        val rows = tableText.lines()
            .filter { it.contains(" | ") }
            .map { line -> line.split(" | ").map { it.trim() } }
            .filter { it.first().isNotBlank() }   // a data row with no description is a totals row
        if (rows.size < 2) return null
        val columnCount = rows.groupingBy { it.size }.eachCount().maxBy { it.value }.key
        val usable = rows.filter { it.size == columnCount }
        if (usable.size < 2 || usable.size < rows.size) return null
        val numericColumns = (1 until columnCount).filter { c ->
            usable.all { parseAmount(it[c]) != null }
        }
        if (numericColumns.isEmpty()) return null

        fun columnSum(c: Int) = usable.sumOf { parseAmount(it[c])!! }

        // The value column alone (net-priced or gross-priced documents), else value+VAT pairs.
        val single = numericColumns.firstOrNull { c -> matches(columnSum(c), expectedTotal) }
        val pair = if (single == null) {
            numericColumns.flatMap { a -> numericColumns.map { b -> a to b } }
                .firstOrNull { (a, b) -> a != b && matches(columnSum(a) + columnSum(b), expectedTotal) }
        } else null
        val valueColumn = single ?: pair?.first ?: return null

        // Quantity x unit-price columns that reproduce the value column row by row; without a
        // consistent pair the value itself becomes a quantity-1 unit price.
        val qtyUnit = numericColumns.flatMap { q -> numericColumns.map { u -> q to u } }
            .firstOrNull { (q, u) ->
                q != u && q != valueColumn && u != valueColumn &&
                    usable.all { row ->
                        matches(parseAmount(row[q])!! * parseAmount(row[u])!!, parseAmount(row[valueColumn])!!)
                    }
            }
        return usable.map { row ->
            val value = parseAmount(row[valueColumn])!!
            if (qtyUnit != null) {
                Item(row[0], parseAmount(row[qtyUnit.first])!!, parseAmount(row[qtyUnit.second])!!)
            } else {
                Item(row[0], 1.0, value)
            }
        }
    }

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
            array.put(JSONObject().put("n", item.name).put("q", item.quantity).put("u", item.unitPrice))
        }
        return array.toString()
    }

    fun fromJson(json: String?): List<Item>? = try {
        if (json.isNullOrBlank()) null else {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                Item(o.optString("n"), o.optDouble("q", 1.0), o.optDouble("u", 0.0))
            }.takeIf { it.isNotEmpty() }
        }
    } catch (e: Exception) {
        null
    }
}
