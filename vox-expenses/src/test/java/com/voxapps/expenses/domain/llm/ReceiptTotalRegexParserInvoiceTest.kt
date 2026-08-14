package com.voxapps.expenses.domain.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiptTotalRegexParserInvoiceTest {

    @Test
    fun `an invoice's own labelled total beats the larger pay-this figure`() {
        val result = ReceiptTotalRegexParser.parse(
            """
            Total Factura 22.21
            Sold Anterior 44.42
            Total de Plata 66.63
            """.trimIndent()
        )
        assertEquals(22.21, result.total!!, 0.0)
        assertEquals(22.21, result.invoiceTotal!!, 0.0)
        assertEquals(44.42, result.previousBalance!!, 0.0)
        assertEquals(66.63, result.totalToPay!!, 0.0)
    }

    @Test
    fun `a plain receipt keeps the largest-wins rule`() {
        val result = ReceiptTotalRegexParser.parse("Subtotal 10.00\nTOTAL LEI 12.50")
        assertEquals(12.50, result.total!!, 0.0)
        assertEquals(null, result.invoiceTotal)
    }
}
