package com.voxapps.docread

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A reading that adds up perfectly, to the wrong figure.
 *
 * The fixture is a measured scan of a three-line pharmacy invoice. Its table carries both a value
 * column and a tax column, and the reconstruction handed them over out of order — its own header row
 * reads `0 2 3 4 6 | 5 | 7`, column 6 ahead of column 5 — so a pattern taking the last amount on each
 * row read the tax column throughout. Those rows then summed to 80.71, which the document really does
 * print, and the reading closed. The scan was filed with its net and its tax exchanged.
 *
 * What makes the case worth keeping is that nothing about it looks wrong: every row reconstructs an
 * amount actually on the page, and their sum is actually printed on it. Only the document's own
 * arithmetic distinguishes the two figures — 80.71 is 21% of 384.29 and the two add to 465.00, so the
 * smaller is the tax on the larger, and line items never sum to tax.
 */
class VatColumnIsNotATargetTest {

    private val scan: String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("scan-invoice-vat-column.txt"))
            .bufferedReader().use { it.readText() }

    /** As printed: 231,40 + 107,44 + 45,45 = 384,29 net, tax 80,71, together 465,00. */
    private val net = 384.29
    private val tax = 80.71

    @Test
    fun `the tax total is not a figure line items may sum to`() {
        val targets = LineItemBattery.Targets(
            invoiceTotal = 465.00,
            labelledOther = listOf(net, tax, 465.00)
        )
        val accepted = targets.accepted()
        assertTrue("the net has to stay acceptable", accepted.any { kotlin.math.abs(it - net) <= 0.02 })
        assertTrue("the tax must not be acceptable", accepted.none { kotlin.math.abs(it - tax) <= 0.02 })
    }

    /** A balance carried forward pairs with the invoice total arithmetically, but it is not a
     *  fraction of it — the rate bound is what tells the two relationships apart. */
    @Test
    fun `a balance carried forward is still acceptable`() {
        val targets = LineItemBattery.Targets(
            invoiceTotal = 66.63,
            labelledOther = listOf(22.21, 44.42, 66.63)
        )
        val accepted = targets.accepted()
        assertTrue(accepted.any { kotlin.math.abs(it - 22.21) <= 0.02 })
        assertTrue(accepted.any { kotlin.math.abs(it - 44.42) <= 0.02 })
    }

    /**
     * End to end on the scan itself. Reading the tax column is no longer something the document can
     * prove, so this must not come back as three items summing to the tax — either the value column
     * is read, or nothing is, and an empty result is the safe half of that.
     */
    @Test
    fun `the scan no longer reads its tax column as line items`() {
        val result = ScanReading.of(scan, TableItemsPreParse.plainText(scan))
        val items = result.items
        if (items != null) {
            val sum = items.sumOf { it.quantity * it.unitPrice }
            assertNotEquals("the tax column must not be read as the line values", tax, sum, 0.02)
        }
    }
}
