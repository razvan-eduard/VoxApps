package com.voxapps.vision.ocr

import com.paddle.ocr.model.OCRResult

/**
 * Reassembles detection boxes into printed rows before the text ever leaves Vision.
 *
 * The detector finds boxes; nothing about its output order respects rows. Emitted as-is, a
 * slightly skewed photo or a multi-column table arrives downstream column-first or interleaved —
 * a single printed line scattered across the text as three fragments, an amount separated from
 * the label that names it. No consumer can repair that afterwards: the LLM is asked to
 * reconstruct a table from confetti, and deterministic extraction walks lines that never existed
 * on paper.
 *
 * Boxes whose vertical spans overlap by most of the smaller height are the same printed row —
 * ink from one line overlaps itself far more than it overlaps its neighbours, which holds under
 * the skew a handheld photo actually has. Rows read top to bottom, cells inside a row left to
 * right, cells joined with spaces: exactly the order a person reads the document in.
 */
object RowClusterer {

    private const val MIN_OVERLAP_RATIO = 0.5f

    /** The recognized text in reading order, one printed row per line. */
    fun toText(results: List<OCRResult>): String = toTextFromCells(cellsOf(results))

    /** Shared box->Cell mapping so [TableReconstructor] sees the exact same geometry. */
    fun cellsOf(results: List<OCRResult>): List<Cell> = results.map { r ->
        val ys = r.box.points.map { it.y }
        val xs = r.box.points.map { it.x }
        Cell(r.text, xs.min(), ys.min(), ys.max(), xs.max())
    }

    /** The geometry core, on plain numbers — what the tests drive directly. */
    fun toTextFromCells(cells: List<Cell>): String =
        cluster(cells).joinToString("\n") { row ->
            row.sortedBy { it.xLeft }.joinToString(" ") { it.text }
        }

    /** The same clustering, kept as rows of cells — for a caller that needs each printed row's
     *  geometry alongside its text rather than the joined string. */
    fun rowsOfCells(cells: List<Cell>): List<List<Cell>> = cluster(cells)

    class Cell(val text: String, val xLeft: Float, val yTop: Float, val yBottom: Float, val xRight: Float = xLeft) {
        internal val height get() = yBottom - yTop
    }

    private class Row(first: Cell) {
        val cells = mutableListOf(first)
        var yTop = first.yTop
        var yBottom = first.yBottom

        fun overlapRatio(cell: Cell): Float {
            val overlap = minOf(yBottom, cell.yBottom) - maxOf(yTop, cell.yTop)
            val reference = minOf(height, cell.height)
            return if (reference <= 0f) 0f else overlap / reference
        }

        fun add(cell: Cell) {
            cells += cell
            yTop = minOf(yTop, cell.yTop)
            yBottom = maxOf(yBottom, cell.yBottom)
        }

        private val height get() = yBottom - yTop
    }

    private fun cluster(input: List<Cell>): List<List<Cell>> {
        val cells = input.filter { it.text.isNotBlank() }
            .sortedBy { (it.yTop + it.yBottom) / 2f }

        val rows = mutableListOf<Row>()
        for (cell in cells) {
            // Cells arrive in y order, so only the most recent rows can still match; scanning a
            // couple back tolerates the box that starts slightly above the row it belongs to.
            val row = rows.takeLast(3).filter { it.overlapRatio(cell) >= MIN_OVERLAP_RATIO }
                .maxByOrNull { it.overlapRatio(cell) }
            if (row != null) row.add(cell) else rows += Row(cell)
        }
        return rows.sortedBy { it.yTop }.map { it.cells }
    }
}
