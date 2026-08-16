package com.voxapps.docread

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

    /** The three totals as a set, so a caller can hand them round and get them back corrected. */
    data class Totals(
        val grandTotal: Double?,
        val invoiceTotal: Double?,
        val previousBalance: Double?
    )

    /**
     * Re-assigns the three totals from the figures alone, for a document whose captions did not land
     * beside the amounts they name.
     *
     * A totals block is a column of captions and a column of figures, and when the two are read a
     * row out of step every amount ends up attached to its neighbour's word. The figures themselves
     * are correct — it is only the pairing that is wrong — so the identity can put them back:
     * [printed] holds the amounts the document shows, the largest of them is what is actually due,
     * and the two that add up to it are this invoice's charges and the balance carried into it.
     *
     * Which of that pair is which does not follow from the identity, since addition does not care
     * about order. [itemsSum] settles it: the invoice's own charges are what its line items add up
     * to, plus tax and nothing else, so the component sitting a plausible tax rate above the items
     * is the invoice's and the other is history. Requiring that sum also means this can only run
     * once the items have been read and proven, which is the point at which the page is understood
     * well enough to correct.
     *
     * Refuses in every other case, returning [totals] untouched: no labelled balance to begin with,
     * more than one pair that adds up, neither component or both plausible as the invoice's own.
     * A receipt that prints cash tendered and change also has two figures adding to a third, and
     * nothing here may turn the change into someone's unpaid history.
     */
    fun repair(totals: Totals, printed: List<Double>, itemsSum: Double?): Totals {
        // Only a document that names a carried balance can have one, and only a contradiction is
        // worth correcting — a reading that already adds up is a reading to leave alone.
        if (totals.previousBalance == null || itemsSum == null || itemsSum <= 0.0) return totals
        if (reconcile(totals.grandTotal, totals.invoiceTotal, totals.previousBalance) == Verdict.RECONCILED) {
            return totals
        }

        val amounts = (printed + listOfNotNull(totals.grandTotal, totals.invoiceTotal, totals.previousBalance))
            .filter { it > 0.0 }
            .distinct()
        val grand = amounts.maxOrNull() ?: return totals

        val pairs = amounts.filter { it < grand }.let { parts ->
            parts.flatMapIndexed { index, a ->
                parts.drop(index + 1)
                    .filter { b -> abs(a + b - grand) <= TOLERANCE }
                    .map { b -> a to b }
            }
        }
        val pair = pairs.singleOrNull() ?: return totals

        val plausibleAsInvoice = listOf(pair.first, pair.second).filter { candidate ->
            candidate >= itemsSum - TOLERANCE && (candidate - itemsSum) / itemsSum <= MAX_TAX_RATE
        }
        val invoice = plausibleAsInvoice.singleOrNull() ?: return totals

        return Totals(
            grandTotal = grand,
            invoiceTotal = invoice,
            previousBalance = grand - invoice
        )
    }

    /** No tax rate in the jurisdictions these documents come from reaches this, so a component
     *  further above the items' sum than this is not the same invoice's own total. */
    private const val MAX_TAX_RATE = 0.30

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
