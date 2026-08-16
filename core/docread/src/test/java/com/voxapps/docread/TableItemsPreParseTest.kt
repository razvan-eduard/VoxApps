package com.voxapps.docread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TableItemsPreParseTest {

    private val tableText = """
        FACTURA Serie: FPHB
        Tarif colectare reciclabile | 2 | 2.35 | 4.70 | 0.99
        Tarif biodeseuri | 2 | 1.62 | 3.24 | 0.68
        Tarif reziduale | 2 | 5.21 | 10.42 | 2.18
        Total Factura 22.21
    """.trimIndent()

    @Test
    fun `items accepted when value column plus VAT column matches the invoice total`() {
        // 4.70+3.24+10.42 = 18.36; +0.99+0.68+2.18 = 22.21
        val items = TableItemsPreParse.parse(tableText, 22.21)!!
        assertEquals(3, items.size)
        assertEquals("Tarif colectare reciclabile", items[0].name)
        assertEquals(2.0, items[0].quantity, 0.0)
        assertEquals(2.35, items[0].unitPrice, 0.0)
    }

    @Test
    fun `items accepted when the value column alone matches`() {
        val items = TableItemsPreParse.parse(tableText, 18.36)!!
        assertEquals(3, items.size)
        assertEquals(1.62, items[1].unitPrice, 0.0)
    }

    @Test
    fun `no column combination matching the total rejects the parse`() {
        assertNull(TableItemsPreParse.parse(tableText, 66.63))
    }

    @Test
    fun `descriptionless totals rows never count as items`() {
        val withTotalsRow = tableText.replace(
            "Total Factura 22.21",
            " | - | - | 18.36 | 3.85\nTotal Factura 22.21"
        )
        val items = TableItemsPreParse.parse(withTotalsRow, 22.21)!!
        assertEquals(3, items.size)
    }

    @Test
    fun `json round trip preserves items`() {
        val items = TableItemsPreParse.parse(tableText, 22.21)!!
        assertEquals(items, TableItemsPreParse.fromJson(TableItemsPreParse.toJson(items)))
    }

    // --- Tables whose long descriptions wrap, leaving gaps in every column --------------------

    /**
     * A description too long for its cell takes a line of its own, and the figures that belong
     * beside it stay on the line the row began — so the quantity and unit price of one row can sit
     * on a different line from that row's value, and every column ends up with gaps.
     *
     * The value column is nevertheless complete and correct, and the table's own subtotal row says
     * what it comes to. Here rows one and four kept all three figures, rows two and three were
     * split, and the six values still add up to the printed 10.00.
     */
    private val wrappedTable = """
        First service line | - | 2 | 2.35 | 4.70 | -
        continuation of the second description | - | - | - | 1.24 | 0.26
        Second service line | - | 2 | 0.62 | - | -
        continuation of the third description | - | - | 0.6 | 1.20 | 0.25
        Third service line | 4 | 2 | - | - | -
        fourth description continued | - | - | 1 | 2.00 | 0.42
        Fifth service line | - | - | 0.43 | 0.86 | 0.18
        Sixth service line | - | 2 | 0.05 | 0.10 | 0.02
         | - | - | - | 10.10 | 1.13
    """.trimIndent()

    @Test
    fun `a wrapped table is read from the column its own subtotal names`() {
        val items = TableItemsPreParse.parse(wrappedTable, expectedTotal = null)!!

        assertEquals(10.10, items.sumOf { it.quantity * it.unitPrice }, 0.005)
        // Six rows carry a value; the two that lost theirs to wrapping are not items.
        assertEquals(6, items.size)
    }

    /** Where a row kept its quantity and unit price, both are used rather than flattened to one. */
    @Test
    fun `quantities survive on the rows that kept them`() {
        val items = TableItemsPreParse.parse(wrappedTable, expectedTotal = null)!!

        val first = items.first { it.name == "First service line" }
        assertEquals(2.0, first.quantity, 0.0)
        assertEquals(2.35, first.unitPrice, 0.0)
    }

    /**
     * Without the subtotal row nothing states what the column should come to, and no total from the
     * foot matches it either — so the same rows are refused rather than half-read.
     */
    @Test
    fun `a wrapped table with nothing to check against is refused`() {
        val noSubtotal = wrappedTable.lines().dropLast(1).joinToString("\n")

        assertNull(TableItemsPreParse.parse(noSubtotal, expectedTotal = 99.99))
    }

    /**
     * A six-column utility table exactly as one reconstructs off a photograph: a header remnant on
     * the first line, quantities and unit prices scattered across lines by wrapped descriptions,
     * two columns of money, and the table's own subtotal row closing it.
     *
     * Twelve of the seventeen rows carry a value, and only those are items. Both money columns prove
     * themselves against the subtotal row — 18.36 of value and 3.85 of tax are each printed there —
     * which is the case the larger-column rule exists to settle.
     */
    private val photographedTable = """
        2 4 | 0 | 3 | - | - | 0.99
        first tariff line | - | 2 | 2.35 | 4.70 | -
        wrapped remainder of the second | - | - | - | 3.24 | 0.68
        second tariff line | - | 2 | 1.62 | - | -
        wrapped remainder of the third | - | - | - | 3.22 | 0.68
        third tariff line | - | 2 | 1.61 | - | -
        wrapped remainder of the fourth | - | - | 0.6 | 1.20 | 0.25
        fourth tariff line | 4 | 2 | - | - | -
        wrapped remainder of the fifth | - | - | 1 | 2.00 | 0.42
        fifth tariff line | 5 | - | - | - | 0.10
        sixth tariff line | - | - | 0.24 | 0.48 | -
        seventh tariff line | - | - | 0.1 | 0.20 | 0.04
        eighth tariff line | - | - | 0.5 | 1.00 | 0.21
        ninth tariff line | - | - | 0.12 | 0.24 | 0.05
        tenth tariff line | - | - | 0.14 | 0.28 | 0.06
        eleventh tariff line | - | - | 0.7 | 1.40 | 0.29
        twelfth tariff line | - | 2 | 0.2 | 0.40 | 0.08
         | - | - | - | 18.36 | 3.85
    """.trimIndent()

    @Test
    fun `a photographed six column table yields exactly its twelve valued rows`() {
        val items = TableItemsPreParse.parse(photographedTable, expectedTotal = null)!!

        assertEquals(12, items.size)
        assertEquals(18.36, items.sumOf { it.quantity * it.unitPrice }, 0.005)
    }

    /** The tax column sums to a figure the subtotal row prints too; it must not be read as items. */
    @Test
    fun `the tax column never wins over the value column`() {
        val items = TableItemsPreParse.parse(photographedTable, expectedTotal = null)!!

        val sum = items.sumOf { it.quantity * it.unitPrice }
        assertTrue("read the tax column as items", kotlin.math.abs(sum - 3.85) > 0.005)
        assertEquals(18.36, sum, 0.005)
    }

    /** The header remnant carries no value and is therefore not merchandise. */
    @Test
    fun `a header remnant above the table is not an item`() {
        val items = TableItemsPreParse.parse(photographedTable, expectedTotal = null)!!

        assertEquals(0, items.count { it.name.startsWith("2 4") })
    }

    /** Tax rides along only where the document printed it, so an older reader loses nothing. */
    @Test
    fun `tax survives the json round trip and is absent when unknown`() {
        val withTax = listOf(TableItemsPreParse.Item("Service", 2.0, 2.35, vatAmount = 0.99))
        val withoutTax = listOf(TableItemsPreParse.Item("Service", 2.0, 2.35))

        assertEquals(0.99, TableItemsPreParse.fromJson(TableItemsPreParse.toJson(withTax))!!.first().vatAmount!!, 0.0001)
        assertNull(TableItemsPreParse.fromJson(TableItemsPreParse.toJson(withoutTax))!!.first().vatAmount)
        assertTrue("an item with no tax must not carry the key", !TableItemsPreParse.toJson(withoutTax).contains("\"v\""))
    }
}
