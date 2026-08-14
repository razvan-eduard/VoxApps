package com.voxapps.expenses.domain.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
