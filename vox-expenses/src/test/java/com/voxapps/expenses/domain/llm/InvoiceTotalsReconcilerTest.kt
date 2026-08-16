package com.voxapps.expenses.domain.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The figures throughout are one real invoice, read off the document itself: twelve service rows
 * whose values sum to 18.36 net, 3.85 VAT, 22.21 as the invoice's own total, 44.42 carried from
 * before it, 66.63 actually due. It is the scan that prompted this class — the app took 66.63 as
 * the record's amount (correctly, it is what gets paid) and then compared the items against 22.21,
 * which a correct item list would never have matched, because this document prints its rows net.
 */
class InvoiceTotalsReconcilerTest {

    private val net = 18.36
    private val vat = 3.85
    private val invoiceTotal = 22.21
    private val previousBalance = 44.42
    private val grandTotal = 66.63

    @Test
    fun `the three totals of a real invoice reconcile`() {
        assertEquals(
            InvoiceTotalsReconciler.Verdict.RECONCILED,
            InvoiceTotalsReconciler.reconcile(grandTotal, invoiceTotal, previousBalance)
        )
    }

    /** The identity is what catches a misread digit — no label can. */
    @Test
    fun `a misread figure contradicts the others`() {
        assertEquals(
            InvoiceTotalsReconciler.Verdict.CONTRADICTED,
            InvoiceTotalsReconciler.reconcile(grandTotal, 82.21, previousBalance)
        )
    }

    @Test
    fun `a receipt with one total is not testable`() {
        assertEquals(
            InvoiceTotalsReconciler.Verdict.UNTESTABLE,
            InvoiceTotalsReconciler.reconcile(33.99, null, null)
        )
    }

    @Test
    fun `a total lost to OCR is arithmetic, not a guess`() {
        val derived = InvoiceTotalsReconciler.deriveMissing(grandTotal, null, previousBalance)
        assertTrue(derived is InvoiceTotalsReconciler.Derived.InvoiceTotal)
        assertEquals(invoiceTotal, derived!!.value, 0.001)

        val carried = InvoiceTotalsReconciler.deriveMissing(grandTotal, invoiceTotal, null)
        assertTrue(carried is InvoiceTotalsReconciler.Derived.PreviousBalance)
        assertEquals(previousBalance, carried!!.value, 0.001)

        assertNull(InvoiceTotalsReconciler.deriveMissing(grandTotal, invoiceTotal, previousBalance))
        assertNull(InvoiceTotalsReconciler.deriveMissing(grandTotal, null, null))
    }

    /**
     * The case the old check got wrong: this invoice prints its rows NET, so a perfectly read item
     * list sums to 18.36 — neither the invoice total nor the grand total. Rejecting it would throw
     * away a correct extraction.
     */
    @Test
    fun `items printed net match the net subtotal`() {
        assertTrue(
            InvoiceTotalsReconciler.itemsBelong(
                itemsSum = net, invoiceTotal = invoiceTotal, netSubtotal = net, vatTotal = vat
            )
        )
    }

    @Test
    fun `items printed gross match the invoice total, by either route`() {
        assertTrue(InvoiceTotalsReconciler.itemsBelong(invoiceTotal, invoiceTotal, net, vat))
        // net + VAT reaches the same figure on a document that never labels its invoice total
        assertTrue(InvoiceTotalsReconciler.itemsBelong(22.21, invoiceTotal = null, netSubtotal = net, vatTotal = vat))
    }

    /** The junk this invoice actually produced: three fragments of the address and column headers,
     *  summing to nothing the document prints. */
    @Test
    fun `an invented item list matches nothing`() {
        val junkSum = 4 * 3.31 + 6 * 0.45 + 8 * 0.12
        assertFalse(InvoiceTotalsReconciler.itemsBelong(junkSum, invoiceTotal, net, vat))
    }

    /** Items never sum to a balance someone failed to pay last month. */
    @Test
    fun `the grand total is not a target for items`() {
        assertFalse(InvoiceTotalsReconciler.itemsBelong(grandTotal, invoiceTotal, net, vat))
    }

    @Test
    fun `an empty item list is not a match`() {
        assertFalse(InvoiceTotalsReconciler.itemsBelong(0.0, invoiceTotal, net, vat))
    }

    @Test
    fun `rounding across many rows still counts as a match`() {
        assertTrue(InvoiceTotalsReconciler.itemsBelong(net + 0.01, invoiceTotal, net, vat))
        assertFalse(InvoiceTotalsReconciler.itemsBelong(net + 0.5, invoiceTotal, net, vat))
    }

    // --- Re-deriving totals whose captions did not land beside their figures -------------------

    private fun totals(grand: Double?, invoice: Double?, previous: Double?) =
        InvoiceTotalsReconciler.Totals(grand, invoice, previous)

    /**
     * The captions on a scanned invoice can be read a row out of step with the amounts, so each
     * figure arrives attached to its neighbour's word: what is due gets taken for the invoice's own
     * charges, and the record is saved for twice what was actually billed. The figures are right —
     * 22.21 and 44.42 do add up to 66.63 — so the identity plus the items' own sum can re-pair them.
     */
    @Test
    fun `shifted captions are re-paired from the figures and the items sum`() {
        val repaired = InvoiceTotalsReconciler.repair(
            totals = totals(grand = 44.42, invoice = null, previous = 22.21),
            printed = listOf(22.21, 44.42, 66.63, 18.36, 3.85),
            itemsSum = 18.36
        )

        assertEquals(22.21, repaired.invoiceTotal!!, 0.005)
        assertEquals(44.42, repaired.previousBalance!!, 0.005)
        assertEquals(66.63, repaired.grandTotal!!, 0.005)
    }

    /**
     * Cash tendered and change also add up to a third figure, and a shop receipt must come through
     * untouched — nothing here may turn someone's change into a carried balance. No balance was
     * named on the page, which is the gate.
     */
    @Test
    fun `a receipt with no carried balance is never re-derived`() {
        val untouched = totals(grand = 45.00, invoice = 45.00, previous = null)

        assertEquals(
            untouched,
            InvoiceTotalsReconciler.repair(untouched, listOf(45.00, 50.00, 5.00), itemsSum = 45.00)
        )
    }

    /** A reading that already satisfies the identity is a reading to leave exactly as it is. */
    @Test
    fun `totals that already add up are left alone`() {
        val coherent = totals(grand = 66.63, invoice = 22.21, previous = 44.42)

        assertEquals(coherent, InvoiceTotalsReconciler.repair(coherent, listOf(22.21, 44.42, 66.63), 18.36))
    }

    /**
     * Without proven items neither component can be told from the other — addition does not say
     * which of the two came first — so the safe answer is to change nothing.
     */
    @Test
    fun `unproven items leave the pairing ambiguous and untouched`() {
        val shifted = totals(grand = 44.42, invoice = null, previous = 22.21)

        assertEquals(shifted, InvoiceTotalsReconciler.repair(shifted, listOf(22.21, 44.42, 66.63), itemsSum = null))
    }

    /** Both components a plausible tax step above the items is no answer either. */
    @Test
    fun `an ambiguous pair is refused`() {
        // 10.00 and 11.00 both sit a believable tax step above 9.50, so either could be the
        // invoice's own total and the identity has nothing further to say.
        val shifted = totals(grand = 11.00, invoice = null, previous = 10.00)

        assertEquals(shifted, InvoiceTotalsReconciler.repair(shifted, listOf(10.00, 11.00, 21.00), itemsSum = 9.50))
    }
}
