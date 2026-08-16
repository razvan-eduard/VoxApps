package com.voxapps.docread

import kotlin.math.abs

/**
 * Reconciles what the lines come to against what the document says they come to, before and after
 * tax.
 *
 * A document states the same sum more than once — the rows, the subtotal, the tax, the total — and
 * the restatements are the only thing that can confirm any of it. Three figures related by
 * `net + tax = total`, with the rows adding to the first, means a wrong reading of any one of them
 * fails against the others instead of being stored as fact.
 *
 * Two rules keep this transcription rather than arithmetic dressed up as it. **A missing figure is
 * derived only from figures that were read**, never from a rate — a row's share of a printed tax
 * total does not distribute exactly at the cent, so deriving per row would produce a breakdown the
 * page never carried. And **tax is summed only where every row carries one**: a partial column is a
 * reading that lost rows, and adding it up would understate the tax while looking like a total.
 */
object TaxBreakdown {

    /** @property verdict what the restatements say about each other; see [InvoiceTotalsReconciler.Verdict]. */
    data class Resolved(
        val net: Double?,
        val vat: Double?,
        val gross: Double?,
        val verdict: InvoiceTotalsReconciler.Verdict
    )

    /**
     * @param itemNets each row's own amount, before tax.
     * @param itemVats each row's tax where the document printed one — null per row where it did not.
     * @param printedNet the subtotal the document states, where it states one; likewise the others.
     */
    fun resolve(
        itemNets: List<Double>,
        itemVats: List<Double?> = emptyList(),
        printedNet: Double? = null,
        printedVat: Double? = null,
        printedGross: Double? = null
    ): Resolved {
        val itemsNet = itemNets.takeIf { it.isNotEmpty() }?.sum()
        // Only a complete column adds up to anything meaningful.
        val itemsVat = itemVats
            .takeIf { it.isNotEmpty() && it.size == itemNets.size && it.all { vat -> vat != null } }
            ?.filterNotNull()?.sum()

        val net = printedNet ?: itemsNet
        val vat = printedVat
            ?: itemsVat
            ?: if (printedGross != null && net != null) printedGross - net else null
        val gross = printedGross ?: if (net != null && vat != null) net + vat else null

        return Resolved(
            net = net?.let(::toCent),
            vat = vat?.let(::toCent),
            gross = gross?.let(::toCent),
            verdict = verdictOf(itemsNet, itemsVat, printedNet, printedVat, printedGross)
        )
    }

    /**
     * Contradicted the moment any restatement disagrees with another; untestable where the document
     * stated a figure only once and nothing else speaks to it.
     */
    private fun verdictOf(
        itemsNet: Double?,
        itemsVat: Double?,
        printedNet: Double?,
        printedVat: Double?,
        printedGross: Double?
    ): InvoiceTotalsReconciler.Verdict {
        val checks = mutableListOf<Boolean>()
        if (itemsNet != null && printedNet != null) checks += agree(itemsNet, printedNet)
        if (itemsVat != null && printedVat != null) checks += agree(itemsVat, printedVat)
        val net = printedNet ?: itemsNet
        val vat = printedVat ?: itemsVat
        if (net != null && vat != null && printedGross != null) checks += agree(net + vat, printedGross)

        return when {
            checks.isEmpty() -> InvoiceTotalsReconciler.Verdict.UNTESTABLE
            checks.all { it } -> InvoiceTotalsReconciler.Verdict.RECONCILED
            else -> InvoiceTotalsReconciler.Verdict.CONTRADICTED
        }
    }

    /**
     * Rounded to the cent, because a sum of rounded rows carries fractions no document printed.
     *
     * Only the answer is rounded, never the comparisons: rounding before comparing would turn a
     * genuine disagreement of a third of a cent into agreement.
     */
    private fun toCent(value: Double): Double = kotlin.math.round(value * 100.0) / 100.0

    private fun agree(a: Double, b: Double) = abs(a - b) <= TOLERANCE

    /** Several rounded rows drift a cent or two from the subtotal they were rounded towards. */
    private const val TOLERANCE = 0.02
}
