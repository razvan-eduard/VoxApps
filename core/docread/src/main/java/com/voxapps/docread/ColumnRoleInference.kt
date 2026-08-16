package com.voxapps.docread

import kotlin.math.abs

/**
 * Works out what a table's columns hold by looking only at what is in them.
 *
 * No headings, no vocabulary, no language. Once the geometry is known — which cell belongs to which
 * column — the columns say what they are by how they behave, and behaviour survives recognition far
 * better than words do: a heading is a handful of characters that can be misread into nothing, while
 * a column of twelve figures that multiply, sum and divide correctly is evidence twelve times over.
 *
 * Three behaviours are enough to name every column that matters:
 *
 *  - **A product.** Where one column times another equals a third, row after row, those three are
 *    quantity, unit price and value. Two columns can agree by accident on one row; they do not agree
 *    on ten.
 *  - **A constant ratio.** Where one column divided by another is the same figure on every row, the
 *    divisor is a value and the dividend is the tax charged on it, that figure being the rate. This
 *    is the only thing that reliably separates the two, and without it a tax column is a value column
 *    that happens to be smaller — a real invoice's tax column sums to its own printed tax total, so
 *    it proves itself just as convincingly as the wrong answer.
 *  - **A printed sum.** Where a column adds up to a figure the document prints, it is a column the
 *    document itself totalled, which is what a value column is.
 *
 * What this cannot do is invent geometry. Where the table never survived as columns, there is
 * nothing here to reason about, and the reading has to come from somewhere else.
 */
object ColumnRoleInference {

    data class Roles(
        val quantity: Int? = null,
        val unitPrice: Int? = null,
        val value: Int? = null,
        val vat: Int? = null,
        /** The tax rate the ratio revealed, where one was found — 0.21 for a fifth, and so on. */
        val taxRate: Double? = null
    ) {
        fun isEmpty() = quantity == null && unitPrice == null && value == null && vat == null
    }

    /**
     * @param columns one list per column, each holding that column's figure per row and null where
     *  the row left the cell empty — the shape a reconstruction produces once its cells are parsed.
     * @param printedTotals figures the document prints somewhere, against which a column's sum may
     *  be recognised.
     */
    fun infer(columns: List<List<Double?>>, printedTotals: List<Double> = emptyList()): Roles {
        if (columns.size < 2) return Roles()
        val indices = columns.indices.toList()

        // A column totalled by the document is a value column, and the largest such is the value
        // rather than the tax charged on it — tax is a fraction of what it is charged on.
        val totalled = indices.filter { c ->
            val sum = columns[c].filterNotNull().sum()
            sum > 0.0 && printedTotals.any { abs(sum - it) <= TOLERANCE }
        }

        val product = indices.flatMap { q -> indices.map { u -> q to u } }
            .asSequence()
            .filter { (q, u) -> q != u }
            .mapNotNull { (q, u) ->
                indices.firstOrNull { v ->
                    v != q && v != u && multiplies(columns[q], columns[u], columns[v])
                }?.let { v -> Triple(q, u, v) }
            }
            // Where the document totalled a column, the product must land on it.
            .firstOrNull { (_, _, v) -> totalled.isEmpty() || v in totalled }

        val value = product?.third ?: totalled.maxByOrNull { columns[it].filterNotNull().sum() }
        val rate = value?.let { v ->
            indices.filter { it != v && it != product?.first && it != product?.second }
                .firstNotNullOfOrNull { candidate ->
                    constantRatio(columns[candidate], columns[v])?.let { candidate to it }
                }
        }

        return Roles(
            quantity = product?.first,
            unitPrice = product?.second,
            value = value,
            vat = rate?.first,
            taxRate = rate?.second
        )
    }

    /** True when [a] times [b] gives [c] on every row where all three are present, on enough rows to
     *  mean something. A pair of columns can agree once by chance; agreeing repeatedly is structure. */
    private fun multiplies(a: List<Double?>, b: List<Double?>, c: List<Double?>): Boolean {
        var agreements = 0
        for (row in a.indices) {
            val x = a.getOrNull(row) ?: continue
            val y = b.getOrNull(row) ?: continue
            val z = c.getOrNull(row) ?: continue
            if (abs(x * y - z) > TOLERANCE) return false
            agreements++
        }
        return agreements >= MIN_AGREEING_ROWS
    }

    /**
     * The constant [part] is of [whole] across the rows, or null when it is not constant.
     *
     * Bounded to rates a tax authority might actually levy, so a column that happens to be a steady
     * multiple of another — a second currency, a per-unit surcharge — is not mistaken for tax.
     */
    private fun constantRatio(part: List<Double?>, whole: List<Double?>): Double? {
        val ratios = part.indices.mapNotNull { row ->
            val p = part.getOrNull(row) ?: return@mapNotNull null
            val w = whole.getOrNull(row) ?: return@mapNotNull null
            if (w <= 0.0) null else p / w
        }
        if (ratios.size < MIN_AGREEING_ROWS) return null
        val mean = ratios.average()
        if (mean <= MIN_TAX_RATE || mean > MAX_TAX_RATE) return null
        // Rounding to the cent moves a small charge's ratio noticeably, so the tolerance is on the
        // rate itself rather than on each division.
        return if (ratios.all { abs(it - mean) <= RATE_TOLERANCE }) mean else null
    }

    private const val TOLERANCE = 0.02

    /** Below this a coincidence is as likely as a relation. */
    private const val MIN_AGREEING_ROWS = 3

    private const val MIN_TAX_RATE = 0.01
    private const val MAX_TAX_RATE = 0.30

    /** Cent rounding on small amounts moves the implied rate by more than a hair. */
    private const val RATE_TOLERANCE = 0.03
}
