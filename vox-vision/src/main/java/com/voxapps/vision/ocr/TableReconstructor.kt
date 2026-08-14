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

    private const val ROW_ANCHOR_TOLERANCE = 0.6f
    private const val BAND_GAP_FACTOR = 2.0f
    private const val MIN_BAND_MEMBERS = 3
    private const val MIN_BANDS = 2
    private const val MIN_DATA_ROWS = 2

    fun toText(cells: List<RowClusterer.Cell>): String? {
        val clean = cells.filter { it.text.isNotBlank() }
        if (clean.size < 8) return null
        val medianHeight = clean.map { it.yBottom - it.yTop }.sorted()[clean.size / 2]
        if (medianHeight <= 0f) return null

        // 1. Printed rows around non-expanding anchors.
        val printed = mutableListOf<PrintedRow>()
        for (cell in clean.sortedBy { yCenter(it) }) {
            val last = printed.lastOrNull()
            if (last != null && abs(yCenter(cell) - last.anchorY) <= medianHeight * ROW_ANCHOR_TOLERANCE) {
                last.cells += cell
            } else {
                printed += PrintedRow(yCenter(cell), mutableListOf(cell))
            }
        }

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
        val realBands = bands.filter { it.size >= MIN_BAND_MEMBERS }
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

    private fun xCenter(c: RowClusterer.Cell): Float = (c.xLeft + c.xRight) / 2f
    private fun yCenter(c: RowClusterer.Cell): Float = (c.yTop + c.yBottom) / 2f

    private class PrintedRow(val anchorY: Float, val cells: MutableList<RowClusterer.Cell>)
}
