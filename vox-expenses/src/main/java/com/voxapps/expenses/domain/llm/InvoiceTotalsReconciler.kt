package com.voxapps.expenses.domain.llm

import kotlin.math.abs

/**
 * Decides whether an invoice's three totals agree with each other, and whether a list of line items
 * belongs to them.
 *
 * A document that carries a balance prints three legitimate figures — what this invoice charges,
 * what was owed before it, and what is actually due — and they are related by arithmetic:
 *
 *     invoice total + previous balance = grand total
 *
 * That identity is worth more than the labels are. Labels arrive through OCR and can be misread or
 * lost entirely, while the relation either holds or it does not, so it can confirm all three
 * readings at once ([reconcile]) without trusting any word on the page.
 *
 * The same reasoning settles what the items are allowed to sum to. Items belong to the invoice's
 * own charges, never to a carried balance, and a document may print those charges before or after
 * tax — so a correct item list matches the net subtotal, or that subtotal plus its VAT, or the
 * invoice total itself. [itemsBelong] accepts any of them and nothing else.
 */
object InvoiceTotalsReconciler {

    /** Absolute slack for a single comparison — printed figures are rounded to the cent, and a
     *  handful of rounded rows can drift a cent or two from the printed subtotal. */
    private const val TOLERANCE = 0.02

    /** What the arithmetic says about the three totals. */
    enum class Verdict {
        /** invoice + previous = grand, to the cent. All three readings confirm each other. */
        RECONCILED,

        /** The three are present but do not add up: at least one was misread. */
        CONTRADICTED,

        /** Not enough of them were found to test the identity — the ordinary case for a receipt,
         *  which prints one total and carries no balance. */
        UNTESTABLE
    }

    fun reconcile(grandTotal: Double?, invoiceTotal: Double?, previousBalance: Double?): Verdict {
        if (grandTotal == null || invoiceTotal == null || previousBalance == null) return Verdict.UNTESTABLE
        return if (abs(invoiceTotal + previousBalance - grandTotal) <= TOLERANCE) {
            Verdict.RECONCILED
        } else {
            Verdict.CONTRADICTED
        }
    }

    /**
     * The figure a missing total must have been, derived from the two that were read.
     *
     * A label lost to OCR does not have to cost the field: on a document where the other two are
     * present the third is arithmetic, not a guess. Returns null when fewer than two are known, or
     * when the one asked for is already there.
     */
    fun deriveMissing(grandTotal: Double?, invoiceTotal: Double?, previousBalance: Double?): Derived? = when {
        grandTotal == null && invoiceTotal != null && previousBalance != null ->
            Derived.GrandTotal(invoiceTotal + previousBalance)
        invoiceTotal == null && grandTotal != null && previousBalance != null ->
            Derived.InvoiceTotal(grandTotal - previousBalance)
        previousBalance == null && grandTotal != null && invoiceTotal != null ->
            Derived.PreviousBalance(grandTotal - invoiceTotal)
        else -> null
    }

    sealed interface Derived {
        val value: Double
        @JvmInline value class GrandTotal(override val value: Double) : Derived
        @JvmInline value class InvoiceTotal(override val value: Double) : Derived
        @JvmInline value class PreviousBalance(override val value: Double) : Derived
    }

    /**
     * Whether [itemsSum] is a figure this document's items are allowed to add up to.
     *
     * [invoiceTotal] is the invoice's own charges (tax included, as printed); [netSubtotal] and
     * [vatTotal] are the pre-tax subtotal and its tax when the document separates them. A match
     * against any of the three legitimate targets is proof the items were read correctly — which is
     * the point: it turns "the numbers look plausible" into something the app can actually verify.
     */
    fun itemsBelong(
        itemsSum: Double,
        invoiceTotal: Double?,
        netSubtotal: Double? = null,
        vatTotal: Double? = null
    ): Boolean {
        if (itemsSum <= 0.0) return false
        return acceptedTargets(invoiceTotal, netSubtotal, vatTotal).any { abs(itemsSum - it) <= TOLERANCE }
    }

    /** Every figure [itemsBelong] would accept, for a caller that wants to say which one matched. */
    fun acceptedTargets(
        invoiceTotal: Double?,
        netSubtotal: Double? = null,
        vatTotal: Double? = null
    ): List<Double> = listOfNotNull(
        invoiceTotal,
        netSubtotal,
        if (netSubtotal != null && vatTotal != null) netSubtotal + vatTotal else null
    )
}
