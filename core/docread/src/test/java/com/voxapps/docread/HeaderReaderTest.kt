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

    /** The designators the app supplies, as it supplies them at run time. */
    private val legalForms = listOf("SRL", "S.R.L.", "SA", "PFA", "GmbH", "AG", "Ltd", "SAS", "BV", "SL")

    private fun shipped(): ReceiptTemplates.Compiled {
        val file = listOf(
            java.io.File("src/main/assets/schemas/receipt_templates.json"),
            java.io.File("core/docread/src/main/assets/schemas/receipt_templates.json")
        ).first { it.exists() }
        return ReceiptTemplates.compiled(
            com.google.gson.Gson().fromJson(file.readText(), ReceiptTemplateSchema::class.java)
        )
    }

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

    // --- The letterhead as it actually arrives ------------------------------------------------

    /**
     * Verbatim from a real scan. Two things about it defeat a reading built on captions alone: the
     * word introducing the supplier came through as "umnizor", and the person being billed is named
     * on the page in the same shape as the company billing them.
     */
    private val realLetterhead = """
        umnizor SC BIN GO SOLUTIONS SRL
        Nr. .ord. J1994008222405
        CIF registru com/an:
        Capital social: 6,700,000RON Client: VOICU EDUARD RAZVAN
        Data: 09/07/2026
        Scadenta: 24/07/2026
    """.trimIndent()

    @Test
    fun `a mangled caption does not cost the vendor`() {
        val library = shipped()

        val fields = HeaderReader.read(
            headerText = realLetterhead,
            templates = library.headers,
            captions = library.captions,
            legalForms = legalForms
        )

        assertEquals("SC BIN GO SOLUTIONS SRL", fields.vendor)
        assertEquals(HeaderReader.SOURCE_LEGAL_FORM, fields.vendorSource)
    }

    /** The person who paid must never land in the field meant for who was paid. */
    @Test
    fun `the buyer is never read as the vendor`() {
        val library = shipped()

        val fields = HeaderReader.read(
            headerText = realLetterhead,
            templates = library.headers,
            captions = library.captions,
            legalForms = legalForms
        )

        assertTrue("read the customer as the vendor: ${fields.vendor}",
            fields.vendor?.contains("VOICU") != true)
    }

    /** A document prints when it was issued and when it falls due; the record takes the first. */
    @Test
    fun `the due date is not mistaken for the invoice date`() {
        val library = shipped()

        val fields = HeaderReader.read(
            headerText = realLetterhead,
            templates = library.headers,
            captions = library.captions,
            legalForms = legalForms
        )

        assertEquals("09/07/2026", fields.date)
    }

    /**
     * The buyer named first, the seller second — the order some layouts print them in. Position
     * cannot decide this, only knowing which words name whom.
     */
    @Test
    fun `the seller is found even when the buyer is printed above it`() {
        val library = shipped()
        val header = """
            Client: POPESCU MARIA
            Cumparator: SC CUMPARATOR TEST SRL
            Furnizor: SC VANZATOR REAL SRL
        """.trimIndent()

        val fields = HeaderReader.read(header, library.headers, library.captions, legalForms)

        assertEquals("SC VANZATOR REAL SRL", fields.vendor)
    }

    /** German and French letterheads read through the same words, with no code per language. */
    @Test
    fun `other languages read through their own captions`() {
        val library = shipped()

        val german = HeaderReader.read(
            "Kunde: Max Mustermann\nLieferant: Beispiel Handels GmbH\nRechnungsdatum: 09.07.2026",
            library.headers, library.captions, legalForms
        )
        assertEquals("Beispiel Handels GmbH", german.vendor)
        assertEquals("09.07.2026", german.date)

        val french = HeaderReader.read(
            "Client: Jean Dupont\nFournisseur: Exemple Commerce SAS\nDate de facture: 09/07/2026",
            library.headers, library.captions, legalForms
        )
        assertEquals("Exemple Commerce SAS", french.vendor)
    }
}
