package com.voxapps.docread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Column detection against the heading rows of two real scans.
 *
 * Both are verbatim recognition output, with the damage that implies: one prints its heading row
 * wrapped over two printed lines, so its later columns are read before its earlier ones, and the
 * other has its headings scattered the length of a pre-printed form. Neither is a case where reading
 * the headings left to right gives the right answer, which is the point — the layout is proposed for
 * the arithmetic to judge, and the route that does not depend on order is preferred where it exists.
 */
class ColumnHeaderDetectorTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "missing $name" }
            .bufferedReader().use { it.readText() }

    private fun shippedColumns(): List<CompiledColumns> {
        val file = listOf(
            java.io.File("src/main/assets/schemas/receipt_templates.json"),
            java.io.File("core/docread/src/main/assets/schemas/receipt_templates.json"),
            java.io.File("../core/docread/src/main/assets/schemas/receipt_templates.json")
        ).first { it.exists() }
        return ReceiptTemplates.compiled(
            com.google.gson.Gson().fromJson(file.readText(), ReceiptTemplateSchema::class.java)
        ).columns
    }

    /**
     * The invoice numbers its own columns and prints `5(3x4)` — the fifth column is the third times
     * the fourth. Recognition mangles the digits around it but the shape survives, and three digits
     * are enough to place quantity, unit price and value without trusting the order the headings
     * were read in.
     */
    @Test
    fun `a numbered form yields its layout from the relation it prints`() {
        val header = fixture("scan-invoice-single.txt")
            .substringAfter(ReceiptSections.HEADER_MARKER)
            .substringBefore(ReceiptSections.ITEMS_MARKER)

        val layouts = ColumnHeaderDetector.detect(header, shippedColumns())

        assertTrue("nothing detected in a heading row that names every column", layouts.isNotEmpty())
        val relational = layouts.filter { it.fromPrintedRelation }
        if (relational.isNotEmpty()) {
            val layout = relational.first()
            assertTrue(layout.roles.contains(ColumnLayout.Role.QTY))
            assertTrue(layout.roles.contains(ColumnLayout.Role.UNIT))
            assertTrue(layout.roles.contains(ColumnLayout.Role.VALUE))
            // Quantity and unit price are what produce the value, so both precede it.
            val value = layout.roles.indexOf(ColumnLayout.Role.VALUE)
            assertTrue(layout.roles.indexOf(ColumnLayout.Role.QTY) < value)
            assertTrue(layout.roles.indexOf(ColumnLayout.Role.UNIT) < value)
        }
    }

    /** However the layout was found, it must say how many columns carry figures — that count is what
     *  turns a run of numbers into rows when the lines holding them did not survive. */
    @Test
    fun `a detected layout knows which of its columns hold figures`() {
        val header = fixture("scan-invoice-single.txt")
            .substringAfter(ReceiptSections.HEADER_MARKER)
            .substringBefore(ReceiptSections.ITEMS_MARKER)

        val layout = ColumnHeaderDetector.detect(header, shippedColumns()).first()

        assertTrue(layout.numericRoles.size >= 2)
        assertTrue(layout.numericRoles.all { it in ColumnLayout.NUMERIC })
    }

    /** The pre-printed form names its columns too, though scattered the length of the page. */
    @Test
    fun `the form's scattered headings are still recognised`() {
        val layouts = ColumnHeaderDetector.detect(fixture("scan-form-noreconstruction.txt"), shippedColumns())

        assertTrue("no layout found on a form that names every column", layouts.isNotEmpty())
    }

    /** Ordinary prose names no columns, and must yield no layout rather than a hopeful one. */
    @Test
    fun `text that is not a heading row yields nothing`() {
        assertEquals(0, ColumnHeaderDetector.detect("thank you for shopping with us", shippedColumns()).size)
    }

    /**
     * The same table, headed in each of the app's four languages, must read the same.
     *
     * This is what the word lists are for. Nothing here is translated in code: a language is a block
     * of terms in the schema, and a document headed in one the app has never been used in reads as
     * soon as somebody adds the words.
     */
    @Test
    fun `a heading row reads the same in every language the app speaks`() {
        val expected = listOf(
            ColumnLayout.Role.NR, ColumnLayout.Role.DESC, ColumnLayout.Role.QTY,
            ColumnLayout.Role.UNIT, ColumnLayout.Role.VALUE, ColumnLayout.Role.VAT
        )
        val headings = mapOf(
            "en" to "No  Description  Qty  Unit Price  Amount  VAT",
            "ro" to "Nr crt  Denumirea produselor  Cantitate  Pretul unitar  Valoare  Valoare TVA",
            "de" to "Pos  Bezeichnung  Menge  Einzelpreis  Betrag  MwSt",
            "fr" to "No  Designation  Quantite  Prix unitaire  Montant HT  TVA"
        )

        for ((language, row) in headings) {
            val layouts = ColumnHeaderDetector.detect(row, shippedColumns())
            assertTrue(
                "$language read as ${layouts.map { it.roles }}",
                layouts.any { it.roles == expected }
            )
        }
    }

    /**
     * A rate quoted in a letterhead carries the same word as a tax column heading. Read as a column
     * it displaces every column after it, so the heading row is looked at before the whole region.
     */
    @Test
    fun `a tax rate printed above the table is not read as a column`() {
        val header = """
            Cota TVA: 21%
            Valoarea -RON Valoare TVA-RON
            Nr. Denumirea produselor U.M. Cantitate Pretul unitar
        """.trimIndent()

        val layout = ColumnHeaderDetector.detect(header, shippedColumns()).first()

        assertEquals(
            listOf(
                ColumnLayout.Role.NR, ColumnLayout.Role.DESC, ColumnLayout.Role.UM,
                ColumnLayout.Role.QTY, ColumnLayout.Role.UNIT, ColumnLayout.Role.VALUE,
                ColumnLayout.Role.VAT
            ),
            layout.roles
        )
    }
}
