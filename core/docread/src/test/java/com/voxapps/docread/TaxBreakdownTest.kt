package com.voxapps.docread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The restatements a document makes about the same sum, checked against each other.
 *
 * The figures are the measured ones from a real invoice: twelve rows coming to 18.36 before tax,
 * 3.85 of tax on them, 22.21 charged.
 */
class TaxBreakdownTest {

    private val rowNets = listOf(4.70, 3.24, 3.22, 1.20, 2.00, 0.48, 0.20, 1.00, 0.24, 0.28, 1.40, 0.40)
    private val rowVats = listOf(0.99, 0.68, 0.68, 0.25, 0.42, 0.10, 0.04, 0.21, 0.05, 0.06, 0.29, 0.08)

    @Test
    fun `rows confirming the printed figures reconcile`() {
        val resolved = TaxBreakdown.resolve(rowNets, rowVats, printedNet = 18.36, printedVat = 3.85, printedGross = 22.21)

        assertEquals(InvoiceTotalsReconciler.Verdict.RECONCILED, resolved.verdict)
        assertEquals(18.36, resolved.net!!, 0.005)
        assertEquals(3.85, resolved.vat!!, 0.005)
        assertEquals(22.21, resolved.gross!!, 0.005)
    }

    /** What the document did not state follows from what it did — from figures, never from a rate. */
    @Test
    fun `an unstated total is derived from the ones that were read`() {
        val resolved = TaxBreakdown.resolve(rowNets, rowVats)

        assertEquals(18.36, resolved.net!!, 0.005)
        assertEquals(3.85, resolved.vat!!, 0.005)
        assertEquals(22.21, resolved.gross!!, 0.005)
    }

    /**
     * A total and a subtotal give the tax between them without any row carrying one — but they do
     * not *confirm* it. Deriving the tax from the difference and then checking that the three add up
     * is true by construction, so the verdict stays untestable: the figure is usable, and nothing
     * has vouched for it.
     */
    @Test
    fun `tax follows from the total and the subtotal, and proves nothing`() {
        val resolved = TaxBreakdown.resolve(rowNets, printedGross = 22.21)

        assertEquals(3.85, resolved.vat!!, 0.005)
        assertEquals(InvoiceTotalsReconciler.Verdict.UNTESTABLE, resolved.verdict)
    }

    /** A misread figure fails against the others rather than being stored as fact. */
    @Test
    fun `a printed figure the rows contradict is reported`() {
        val resolved = TaxBreakdown.resolve(rowNets, rowVats, printedNet = 20.00, printedVat = 3.85, printedGross = 22.21)

        assertEquals(InvoiceTotalsReconciler.Verdict.CONTRADICTED, resolved.verdict)
    }

    /**
     * A tax column that survived on only some rows is a reading that lost rows. Adding it up would
     * understate the tax while looking like a total, so it is not summed at all.
     */
    @Test
    fun `a partial tax column is not summed`() {
        val partial = rowVats.mapIndexed { index, vat -> if (index < 3) vat else null }

        val resolved = TaxBreakdown.resolve(rowNets, partial)

        assertEquals(18.36, resolved.net!!, 0.005)
        assertNull(resolved.vat)
        assertNull(resolved.gross)
    }

    /** With nothing to compare against, nothing is claimed either way. */
    @Test
    fun `a single stated figure is untestable`() {
        assertEquals(
            InvoiceTotalsReconciler.Verdict.UNTESTABLE,
            TaxBreakdown.resolve(emptyList(), emptyList(), printedNet = 18.36).verdict
        )
    }

    /** A sum of rounded rows carries fractions no document printed; the answer is a cent figure. */
    @Test
    fun `the answer is rounded to the cent`() {
        val resolved = TaxBreakdown.resolve(listOf(0.105, 0.105, 0.105))

        assertEquals(0.32, resolved.net!!, 0.0001)
    }
}
