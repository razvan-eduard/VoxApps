package com.voxapps.docread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading the fields no sum can confirm, on the letterhead of a real scan.
 *
 * This is the weakest reading in the module and is meant to be: a wrong vendor costs a word in a
 * field a person sees, while a wrong amount costs a record they do not. What it must not do is
 * return a paragraph, or a name with the company's address welded to it.
 */
class HeaderReaderTest {

    private fun shippedHeaders(): List<CompiledHeader> {
        val file = listOf(
            java.io.File("src/main/assets/schemas/receipt_templates.json"),
            java.io.File("core/docread/src/main/assets/schemas/receipt_templates.json")
        ).first { it.exists() }
        return ReceiptTemplates.compiled(
            com.google.gson.Gson().fromJson(file.readText(), ReceiptTemplateSchema::class.java)
        ).headers
    }

    @Test
    fun `a supplier line gives the name without the address welded to it`() {
        val header = """
            Furnizor: SC BIN GO SOLUTIONS SRL Sediul: Strada Soseaua Giurgiului
            Nr. factura: 9123255
            Data: 09/07/2026
        """.trimIndent()

        val fields = HeaderReader.read(header, shippedHeaders())

        assertEquals("SC BIN GO SOLUTIONS SRL", fields.vendor)
        assertEquals("09/07/2026", fields.date)
        assertTrue(fields.invoiceNumber != null)
    }

    @Test
    fun `an English letterhead reads the same fields`() {
        val header = """
            Supplier: ACME TRADING LTD
            Invoice No: INV-2026-0042
            Invoice Date: 09/07/2026
        """.trimIndent()

        val fields = HeaderReader.read(header, shippedHeaders())

        assertEquals("ACME TRADING LTD", fields.vendor)
        assertEquals("INV-2026-0042", fields.invoiceNumber)
        assertEquals("09/07/2026", fields.date)
    }

    /** Nothing matching leaves the fields empty rather than hopeful: a blank is a person's to fill. */
    @Test
    fun `prose yields nothing`() {
        val fields = HeaderReader.read("thank you for shopping with us today", shippedHeaders())

        assertTrue(fields.isEmpty())
        assertNull(fields.templateId)
    }

    /** A pattern that ran into a paragraph is a failure, not a long vendor. */
    @Test
    fun `a runaway match is refused rather than stored`() {
        val header = "Furnizor: " + "x".repeat(400)

        assertNull(HeaderReader.read(header, shippedHeaders()).vendor)
    }
}
