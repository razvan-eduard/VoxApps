package com.voxapps.vision.ocr

import kotlin.math.abs

/**
 * Table-aware reassembly for [com.voxapps.ipc.VoxOcrRequest.tableMode] documents (invoices, tariff
 * sheets — anything whose payload is rows x columns of numbers).
 *
 * [RowClusterer] alone loses tables two ways, both observed on real invoices: a dense block of
 * short rows chain-merges (each added cell grows the row's span, which then swallows the next
 * row — five items emitted as one line, column-major), and a wrapped description cell lands as its
 * own printed row between two data rows, tearing the item apart from its numbers.
 *
 * This reconstruction is geometry-first and self-distrusting:
 *  1. Printed rows form around NON-expanding anchors (a row's reference y never moves), so dense
 *     rows cannot chain-merge.
 *  2. Money-shaped cells cluster into vertical COLUMN BANDS by x-center; only bands with three or
 *     more members count — real columns repeat, stray numbers don't.
 *  3. Printed rows holding numbers in two or more distinct bands are DATA rows (one logical item
 *     each); number-free printed rows inside the table's span are wrapped descriptions and attach
 *     to the nearest data row.
 *  4. Each logical row is emitted as `description | col | col | ...` (bands left to right, `-` for
 *     an empty cell); everything above and below the table passes through in reading order.
 *
 * Anything short of a confident table — under two qualifying bands, under two data rows — returns
 * null and the caller falls back to [RowClusterer]'s plain reading order.
 */
object TableReconstructor {

    /** A money/quantity-shaped token: digits with optional thousands/decimal separators. */
    private val NUMERIC = Regex("""^-?\d{1,4}(?:[.,]\d{3})*(?:[.,]\d{1,2})?$""")

    private const val ROW_ANCHOR_OVERLAP = 0.45f
    private const val BAND_GAP_FACTOR = 2.0f
    private const val MIN_BAND_MEMBERS = 3
    private const val MIN_BANDS = 2
    private const val MIN_DATA_ROWS = 2

    /**
     * The plain reading-order text, line-broken at PRINTED-row boundaries using the same
     * non-expanding anchors as the reconstruction — pure geometry, no column interpretation.
     * [RowClusterer]'s expanding rows chain-merge a dense table's short rows into one line, and a
     * consumer (human or model) reading that line has no way to know several items were glued
     * together; anchored assembly keeps each printed row on its own line. Null when the document
     * is too sparse to bother — the caller falls back to [RowClusterer]'s own text.
     */
    fun plainRowsText(cells: List<RowClusterer.Cell>): String? {
        val clean = cells.filter { it.text.isNotBlank() }
        if (clean.size < 8) return null
        val medianHeight = clean.map { it.yBottom - it.yTop }.sorted()[clean.size / 2]
        if (medianHeight <= 0f) return null
        return assemblePrintedRows(clean, medianHeight)
            .joinToString("\n") { row -> row.cells.sortedBy { it.xLeft }.joinToString(" ") { it.text } }
    }

    fun toText(cells: List<RowClusterer.Cell>): String? {
        val clean = cells.filter { it.text.isNotBlank() }
        if (clean.size < 8) return null
        val medianHeight = clean.map { it.yBottom - it.yTop }.sorted()[clean.size / 2]
        if (medianHeight <= 0f) return null

        val printed = assemblePrintedRows(clean, medianHeight)

        // 2. Column bands over money-shaped cells.
        val numericCells = clean.filter { NUMERIC.matches(it.text.trim()) }
        val bands = mutableListOf<MutableList<RowClusterer.Cell>>()
        for (cell in numericCells.sortedBy { xCenter(it) }) {
            val band = bands.lastOrNull()
            if (band != null && xCenter(cell) - xCenter(band.last()) <= medianHeight * BAND_GAP_FACTOR) {
                band += cell
            } else {
                bands += mutableListOf(cell)
            }
        }
        val qualifying = bands.filter { it.size >= MIN_BAND_MEMBERS }.toMutableList()
        // A leftmost band of small COUNTING integers is the table's own row-number column ("Nr."):
        // its values are distinct and non-decreasing down the page. A quantity column also holds
        // small integers but REPEATS them — the distinctness test tells the two apart.
        if (qualifying.size > MIN_BANDS) {
            val first = qualifying.first()
            val ints = first.sortedBy { yCenter(it) }.map { cell ->
                val t = cell.text.trim()
                if (t.contains('.') || t.contains(',')) null else t.toIntOrNull()?.takeIf { it < 100 }
            }
            val counting = ints.all { it != null } &&
                ints.filterNotNull().zipWithNext().all { (a, b) -> b >= a } &&
                ints.distinct().size >= (ints.size * 2 + 2) / 3
            if (counting) qualifying.removeAt(0)
        }
        val realBands: List<List<RowClusterer.Cell>> = qualifying
        if (realBands.size < MIN_BANDS) return null
        val bandRanges = realBands.map { band ->
            val centers = band.map { xCenter(it) }
            (centers.min() - medianHeight) to (centers.max() + medianHeight)
        }

        fun bandIndexOf(cell: RowClusterer.Cell): Int {
            if (!NUMERIC.matches(cell.text.trim())) return -1
            val x = xCenter(cell)
            return bandRanges.indexOfFirst { (lo, hi) -> x in lo..hi }
        }

        // 3. Data rows vs wrapped-description rows.
        val dataRows = printed.filter { row ->
            row.cells.map { bandIndexOf(it) }.filter { it >= 0 }.distinct().size >= 2
        }
        if (dataRows.size < MIN_DATA_ROWS) return null
        val firstDataY = dataRows.first().anchorY
        val lastDataY = dataRows.last().anchorY

        val logical = LinkedHashMap<PrintedRow, MutableList<PrintedRow>>()
        dataRows.forEach { logical[it] = mutableListOf(it) }
        val above = mutableListOf<PrintedRow>()
        val below = mutableListOf<PrintedRow>()
        for (row in printed) {
            if (row in logical) continue
            when {
                row.anchorY < firstDataY -> above += row
                row.anchorY > lastDataY -> below += row
                else -> {
                    val nearest = dataRows.minBy { abs(it.anchorY - row.anchorY) }
                    logical.getValue(nearest) += row
                }
            }
        }

        // 4. Emission.
        fun plainLine(row: PrintedRow): String =
            row.cells.sortedBy { it.xLeft }.joinToString(" ") { it.text }

        val out = StringBuilder()
        above.forEach { out.appendLine(plainLine(it)) }
        for ((anchor, group) in logical) {
            val groupCells = group.sortedBy { it.anchorY }.flatMap { it.cells.sortedBy { c -> c.xLeft } }
            val description = groupCells
                .filter { bandIndexOf(it) < 0 }
                .joinToString(" ") { it.text }
            val columns = realBands.indices.map { bandIdx ->
                anchor.cells
                    .filter { bandIndexOf(it) == bandIdx }
                    .sortedBy { it.xLeft }
                    .joinToString(" ") { it.text }
                    .ifEmpty { "-" }
            }
            out.appendLine((listOf(description) + columns).joinToString(" | "))
        }
        below.forEach { out.appendLine(plainLine(it)) }
        return out.toString().trimEnd()
    }

    /**
     * Printed rows around non-expanding anchors. Membership is vertical OVERLAP with the row's
     * FIRST cell (never the expanded row bounds — expansion is what chain-merges dense tables),
     * because a wrapped description's box rides at a different baseline than the number printed
     * beside it: center distance split them, ink overlap doesn't. The anchor band is CLAMPED to
     * one median print-row around the cell's center: a wrapped description arrives as one tall
     * box spanning several printed rows, and an unclamped tall anchor overlaps everything near
     * it — the whole table glued into one row. The overlap test likewise measures the candidate
     * against no more than one row-height of itself.
     */
    private fun assemblePrintedRows(
        clean: List<RowClusterer.Cell>,
        medianHeight: Float
    ): List<PrintedRow> {
        val printed = mutableListOf<PrintedRow>()
        for (cell in clean.sortedBy { yCenter(it) }) {
            val row = printed.takeLast(3)
                .filter { it.anchorOverlap(cell, medianHeight) >= ROW_ANCHOR_OVERLAP }
                .maxByOrNull { it.anchorOverlap(cell, medianHeight) }
            if (row != null) row.cells += cell
            else {
                val half = minOf(cell.yBottom - cell.yTop, medianHeight) / 2f
                val cy = yCenter(cell)
                printed += PrintedRow(cy, mutableListOf(cell), cy - half, cy + half)
            }
        }
        return printed
    }

    private fun xCenter(c: RowClusterer.Cell): Float = (c.xLeft + c.xRight) / 2f
    private fun yCenter(c: RowClusterer.Cell): Float = (c.yTop + c.yBottom) / 2f

    private class PrintedRow(
        val anchorY: Float,
        val cells: MutableList<RowClusterer.Cell>,
        private val anchorTop: Float = anchorY,
        private val anchorBottom: Float = anchorY
    ) {
        fun anchorOverlap(cell: RowClusterer.Cell, medianHeight: Float): Float {
            // The candidate's span is clamped the same way as the anchor's: only the one-row-high
            // slice around its center may count, so a tall wrapped box can join exactly the row
            // its center sits on and no other.
            val half = minOf(cell.yBottom - cell.yTop, medianHeight) / 2f
            val cy = (cell.yTop + cell.yBottom) / 2f
            val top = cy - half
            val bottom = cy + half
            val overlap = minOf(anchorBottom, bottom) - maxOf(anchorTop, top)
            val reference = minOf(anchorBottom - anchorTop, bottom - top)
            return if (reference <= 0f) 0f else overlap / reference
        }
    }
}
