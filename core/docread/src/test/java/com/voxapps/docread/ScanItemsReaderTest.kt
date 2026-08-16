package com.voxapps.docread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The reader end to end, on the shape a scanned utility invoice actually arrives in.
 *
 * The fixture is a measured one rather than an invented one: twelve service rows, each a quantity
 * of two at its own unit price, adding up to 18.36 before tax. What makes it worth a test is where
 * the document proves that figure. The foot of the page carries only the grand totals — the invoice
 * total, a balance carried forward, and their sum — while 18.36 is printed *inside* the table, on a
 * row that has amounts but no description, because a total names no product.
 *
 * A reader that looks only at the foot has nothing correct to check twelve rows against, so it
 * refuses them all and the scan yields no items. That refusal is right in itself — better an empty
 * record than an invented one — but the document did print the proof, one region higher.
 */
class ScanItemsReaderTest {

    /** Unit prices as printed; every row is a quantity of two, so values are double these. */
    private val unitPrices = listOf(
        2.35, 1.62, 1.61, 0.60, 1.00, 0.24, 0.10, 0.50, 0.12, 0.14, 0.70, 0.20
    )

    private fun itemRows(): String =
        unitPrices.mapIndexed { index, unit ->
            val value = "%.2f".format(unit * 2)
            "Service ${index + 1} | 2 | ${"%.2f".format(unit)} | $value"
        }.joinToString("\n")

    /** The subtotal line the table itself carries: net and VAT, with the description cell empty. */
    private val totalsRow = " | - | - | 18.36 | 3.85"

    /** The foot as scanned, holding the grand figures only. */
    private val footer = """
        Total Factura
        Sold Anterior 22.21
        Total de Plata 44.42
        66.63
    """.trimIndent()

    private fun document(items: String) = """
        ${ReceiptSections.HEADER_MARKER}
        BIN GO SRL | - | -
        ${ReceiptSections.ITEMS_MARKER}
        $items
        ${ReceiptSections.FOOTER_MARKER}
        $footer
    """.trimIndent()

    /**
     * The totals the parser produces for this scan are themselves wrong — the captions sit one line
     * above their values on this page, so 44.42 is taken for the invoice's own total. The rows still
     * have to be readable, because their proof does not depend on the foot being understood.
     */
    private val totalsAsParsed = ReceiptTotalRegexParser.Result(
        total = 66.63,
        invoiceTotal = 44.42,
        previousBalance = 22.21
    )

    @Test
    fun `rows are read when the table prints their subtotal and the foot does not`() {
        val result = ScanItemsReader.read(document(itemRows() + "\n" + totalsRow), totalsAsParsed)

        assertEquals(12, result?.items?.size)
        val sum = result!!.items.sumOf { it.quantity * it.unitPrice }
        assertEquals(18.36, sum, 0.005)
    }

    /** The totals row is evidence, not merchandise: it must never become a thirteenth item. */
    @Test
    fun `the subtotal row is not itself an item`() {
        val result = ScanItemsReader.read(document(itemRows() + "\n" + totalsRow), totalsAsParsed)

        assertEquals(0, result!!.items.count { it.name.isBlank() })
        assertEquals(0, result.items.count { it.unitPrice == 18.36 || it.unitPrice == 3.85 })
    }

    /**
     * Without that row the same twelve rows are unprovable — nothing printed anywhere on the page
     * equals what they add up to — and unprovable has to stay empty rather than become plausible.
     */
    @Test
    fun `the same rows are refused when nothing printed proves them`() {
        assertNull(ScanItemsReader.read(document(itemRows()), totalsAsParsed))
    }

    /**
     * The same document as it actually arrives once its descriptions are long enough to wrap: the
     * quantity and unit price of a row stay on the line the row began, while its value sits on the
     * line the wrapped text pushed it to. No row proves itself by its own arithmetic any more —
     * yet the value column is complete, and the subtotal the table prints for it says so.
     */
    private fun wrappedItemRows(): String {
        val lines = mutableListOf<String>()
        unitPrices.forEachIndexed { index, unit ->
            val value = "%.2f".format(unit * 2)
            if (index % 2 == 0) {
                // Kept intact.
                lines += "Service ${index + 1} | 2 | ${"%.2f".format(unit)} | $value"
            } else {
                // Split: the row's own line keeps quantity and price, the value lands on the next.
                lines += "Service ${index + 1} | 2 | ${"%.2f".format(unit)} | -"
                lines += "continued description | - | - | $value"
            }
        }
        return lines.joinToString("\n")
    }

    @Test
    fun `rows split across lines by wrapping are still read from the column`() {
        val result = ScanItemsReader.read(document(wrappedItemRows() + "\n" + totalsRow), totalsAsParsed)

        assertEquals(18.36, result!!.items.sumOf { it.quantity * it.unitPrice }, 0.005)
    }
}
