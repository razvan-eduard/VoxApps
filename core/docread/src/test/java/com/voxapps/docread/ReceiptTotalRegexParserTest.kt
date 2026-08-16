package com.voxapps.docread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReceiptTotalRegexParserTest {

    private fun total(text: String) = ReceiptTotalRegexParser.parse(text).total

    @Test
    fun `label and value on separate lines`() {
        // OCR of a tabular document routinely breaks the column between a label and its figure.
        val text = """
            Total Factura
            22.21
            Sold Anterior
            44.42
            Total de Plata
            66.63
        """.trimIndent()
        assertEquals(66.63, total(text)!!, 0.001)
    }

    @Test
    fun `unlabelled carried-over balance never becomes a candidate`() {
        // The larger figure here carries no total label, so it must not win despite being bigger.
        val text = """
            Total de Plata
            66.63
            Sold Anterior
            9999.00
        """.trimIndent()
        assertEquals(66.63, total(text)!!, 0.001)
    }

    @Test
    fun `cash tendered above the total is excluded`() {
        val text = """
            TOTAL LEI 45.00
            Numerar 50.00
            Rest 5.00
        """.trimIndent()
        assertEquals(45.00, total(text)!!, 0.001)
    }

    @Test
    fun `subtotal loses to the total containing it`() {
        val text = """
            Subtotal 30.00
            Total 35.70
        """.trimIndent()
        assertEquals(35.70, total(text)!!, 0.001)
    }

    @Test
    fun `comma decimal separator`() {
        assertEquals(66.63, total("Total de plată 66,63")!!, 0.001)
    }

    @Test
    fun `thousands run with comma decimal`() {
        assertEquals(1234.56, total("Gesamtbetrag 1.234,56")!!, 0.001)
    }

    @Test
    fun `thousands run with dot decimal`() {
        assertEquals(1234.56, total("Amount due 1,234.56")!!, 0.001)
    }

    @Test
    fun `bare integer total`() {
        assertEquals(45.0, total("Total 45")!!, 0.001)
    }

    @Test
    fun `vat rate on the total line is not the total`() {
        assertEquals(35.70, total("Total 35.70 (TVA 19%)")!!, 0.001)
    }

    @Test
    fun `no total label yields null`() {
        val text = """
            Paine 3.50
            Lapte 5.20
            Numerar 10.00
        """.trimIndent()
        assertNull(total(text))
    }

    @Test
    fun `empty text yields null`() {
        assertNull(total(""))
    }
}
