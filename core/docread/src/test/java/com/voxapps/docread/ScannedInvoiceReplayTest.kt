package com.voxapps.docread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A real scan, replayed byte for byte.
 *
 * The fixture is not written by hand and not tidied: it is the exact text one photograph of a utility
 * invoice produced on a phone, markers and misreadings included — descriptions
 * mangled to "Tapentu coletarea sarata", a header remnant standing where a row should be, and the
 * quantities and unit prices of the wrapped rows stranded on lines of their own.
 *
 * Constructed fixtures can be shaped, unconsciously, to fit the code they exercise. This one cannot:
 * it existed before the code that reads it, and it is the whole document rather than the part that
 * suits. What it pins is that the deterministic path extracts this invoice's twelve rows from that
 * text and refuses to invent anything from the rest of it.
 */
class ScannedInvoiceReplayTest {

    private val rawText: String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("scan-invoice-single.txt")) {
            "missing scan fixture"
        }.bufferedReader().use { it.readText() }

    /** The invoice's own charges before tax; the twelve service rows add up to exactly this. */
    private val netSubtotal = 18.36

    @Test
    fun `the twelve rows are read from the real scan`() {
        val plainText = TableItemsPreParse.plainText(rawText)
        val result = ScanReading.of(rawText, plainText)

        assertEquals(12, result.items?.size)
        assertEquals(netSubtotal, result.items!!.sumOf { it.quantity * it.unitPrice }, 0.005)
    }

    /**
     * The three totals as the document prints them: what this invoice charges, what was carried
     * into it, and what is actually due — with the first two adding up to the third.
     */
    @Test
    fun `the three totals come out of the real scan intact`() {
        val result = ScanReading.of(rawText, TableItemsPreParse.plainText(rawText))

        assertEquals(22.21, result.totals.invoiceTotal!!, 0.005)
        assertEquals(44.42, result.totals.previousBalance!!, 0.005)
        assertEquals(66.63, result.totals.total!!, 0.005)
        assertEquals(
            InvoiceTotalsReconciler.Verdict.RECONCILED,
            InvoiceTotalsReconciler.reconcile(
                result.totals.total, result.totals.invoiceTotal, result.totals.previousBalance
            )
        )
    }

    /**
     * Nothing that is not a service row becomes one. The header remnant carries no value, and the
     * subtotal row is the table's evidence about itself rather than something that was bought.
     */
    @Test
    fun `no part of the page that is not an item becomes one`() {
        val items = ScanReading.of(rawText, TableItemsPreParse.plainText(rawText)).items!!

        assertTrue(items.none { it.name.isBlank() })
        assertTrue(items.none { it.unitPrice == netSubtotal || it.unitPrice == 3.85 })
        assertTrue(items.none { it.name.trim() == "2 4" })
    }

    // --- The shipped template library, run over the same real scan ---------------------------

    /** The library exactly as it is bundled in the APK, parsed the way the app parses it. */
    private fun shippedLibrary(): ReceiptTemplates.Compiled {
        val file = listOf(
            java.io.File("src/main/assets/schemas/receipt_templates.json"),
            java.io.File("core/docread/src/main/assets/schemas/receipt_templates.json"),
            java.io.File("../core/docread/src/main/assets/schemas/receipt_templates.json")
        ).first { it.exists() }
        val parsed = com.google.gson.Gson()
            .fromJson(file.readText(), ReceiptTemplateSchema::class.java)
        assertTrue("the shipped library must be usable", ReceiptTemplates.isUsable(parsed))
        return ReceiptTemplates.compiled(parsed)
    }

    /**
     * Every pattern that ships must compile and be judgeable — a row naming no amount could never be
     * proved or disproved, and a footer naming no caption in a mode that needs one reads nothing.
     * Entries are dropped individually rather than failing the file, so a silent shortfall here is
     * exactly the failure this catches.
     */
    @Test
    fun `every shipped template survives compilation`() {
        val file = listOf(
            java.io.File("src/main/assets/schemas/receipt_templates.json"),
            java.io.File("core/docread/src/main/assets/schemas/receipt_templates.json"),
            java.io.File("../core/docread/src/main/assets/schemas/receipt_templates.json")
        ).first { it.exists() }
        val parsed = com.google.gson.Gson()
            .fromJson(file.readText(), ReceiptTemplateSchema::class.java)
        val compiled = ReceiptTemplates.compiled(parsed)

        assertEquals(parsed.items.size, compiled.items.size)
        assertEquals(parsed.footer.size, compiled.footers.size)
        assertEquals(parsed.header.size, compiled.headers.size)
        assertTrue(compiled.items.map { it.id }.toSet().size == compiled.items.size)
    }

    /**
     * The combination search, over the whole shipped library, on the real scan: one of the footer
     * patterns and one of the row patterns have to meet on this document and prove each other.
     */
    @Test
    fun `the shipped library reads the real scan`() {
        val library = shippedLibrary()
        val result = ScanReading.of(
            rawText,
            TableItemsPreParse.plainText(rawText),
            itemTemplates = library.items,
            footerTemplates = library.footers
        )

        assertEquals(12, result.items?.size)
        assertEquals(netSubtotal, result.items!!.sumOf { it.quantity * it.unitPrice }, 0.005)
        assertEquals(22.21, result.totals.invoiceTotal!!, 0.005)
        assertEquals(44.42, result.totals.previousBalance!!, 0.005)
        assertEquals(66.63, result.totals.total!!, 0.005)
    }
}
