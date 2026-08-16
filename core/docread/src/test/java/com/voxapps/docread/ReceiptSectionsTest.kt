package com.voxapps.docread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptSectionsTest {

    private val sectioned = """
        FACTURA Serie: FPHB plain reading order
        ${TableItemsPreParse.TABLE_SECTION_MARKER}
        ${ReceiptSections.HEADER_MARKER}
        Furnizor: SC BIN GO SOLUTIONS SRL
        Capital social: 6,700,000 RON
        ${ReceiptSections.ITEMS_MARKER}
        Tarif reciclabile | 2 | 2.35 | 4.70
        Tarif biodeseuri | 2 | 1.62 | 3.24
        ${ReceiptSections.FOOTER_MARKER}
        Total Factura 22.21
        Sold Anterior 44.42
        Total de Plata 66.63
    """.trimIndent()

    @Test
    fun `each region holds only its own lines`() {
        val s = ReceiptSections.split(sectioned)

        assertTrue(s.marked)
        assertTrue(s.header.contains("Furnizor"))
        assertTrue(s.header.contains("Capital social"))
        assertFalse("an item leaked into the header", s.header.contains("Tarif"))

        assertEquals(2, s.items.lines().size)
        assertTrue(s.items.lines().all { it.contains(" | ") })
        assertFalse("a total leaked into the items", s.items.contains("Total Factura"))

        assertTrue(s.footer.contains("Total Factura 22.21"))
        assertFalse("share capital leaked into the footer", s.footer.contains("Capital social"))
    }

    /**
     * The share capital printed in a letterhead is a large, plausible-looking amount that competes
     * with the real totals under a largest-wins rule. Scoping the search to the footer is what
     * removes it from consideration — the reason the split is worth having at all.
     */
    @Test
    fun `the footer alone yields the document's totals`() {
        val totals = ReceiptTotalRegexParser.parse(ReceiptSections.split(sectioned).footer)

        assertEquals(66.63, totals.total!!, 0.001)
        assertEquals(22.21, totals.invoiceTotal!!, 0.001)
        assertEquals(44.42, totals.previousBalance!!, 0.001)
        assertEquals(
            InvoiceTotalsReconciler.Verdict.RECONCILED,
            InvoiceTotalsReconciler.reconcile(totals.total, totals.invoiceTotal, totals.previousBalance)
        )
    }

    /**
     * A stitched capture recognises each photo separately, so a two-shot scan carries two of every
     * marker. Cutting at the first one loses the second shot entirely — which is what the old
     * reading did, before anything downstream ever saw those lines.
     */
    @Test
    fun `repeated markers from a stitched capture are collected, not truncated`() {
        val twoShots = sectioned + "\n" + sectioned.replace("Tarif biodeseuri", "Tarif reziduale")
        val s = ReceiptSections.split(twoShots)

        assertEquals(4, s.items.lines().size)
        assertTrue(s.items.contains("Tarif reziduale"))
        assertEquals(2, s.footer.lines().count { it.contains("Total Factura") })
    }

    /**
     * Stitch capture is the workflow that reads a dense page best — each shot is a close-up, so the
     * OCR gets far more pixels per character than one wide frame of the whole document. Every shot
     * contributes its own plain reading order, and taking only the first threw the rest away.
     */
    @Test
    fun `every shot of a stitched capture contributes its plain text`() {
        val seam = "--- [photo stitch seam — two overlapping close-up shots were joined here ---"
        val twoShots = sectioned + "\n" + seam + "\n" +
            sectioned.replace("plain reading order", "second shot rows")
        val s = ReceiptSections.split(twoShots)

        assertTrue("the first shot's plain text is missing", s.plain.contains("plain reading order"))
        assertTrue("the second shot's plain text is missing", s.plain.contains("second shot rows"))
        // Still kept out of the sections, so nothing is counted twice.
        assertFalse(s.items.contains("second shot rows"))
    }

    /**
     * A subtotal line printed inside the table arrives as a row with an empty description, because a
     * total names no product. A real scan put "18.36 3.85" there — the figure its twelve rows add up
     * to — while the footer holding that caption carried only the grand totals, so a reader that
     * looked at the footer alone had nothing to check the rows against.
     */
    @Test
    fun `a totals row inside the table is still in the items section`() {
        val withTotalsRow = sectioned.replace(
            "${ReceiptSections.FOOTER_MARKER}",
            " | - | - | 18.36 | 3.85\n${ReceiptSections.FOOTER_MARKER}"
        )
        val s = ReceiptSections.split(withTotalsRow)

        val totalsRows = s.items.lines().filter { it.substringBefore(" | ").isBlank() }
        assertEquals(1, totalsRows.size)
        assertTrue(totalsRows.first().contains("18.36"))
        // And the real item rows keep their descriptions, so the two are told apart by that alone.
        assertEquals(2, s.items.lines().count { it.substringBefore(" | ").isNotBlank() })
    }

    /** A document Vision could not reconstruct carries no markers, and every caller must keep
     *  working exactly as it did before sections existed. */
    @Test
    fun `unmarked text is handed to every caller whole`() {
        val plain = "MEGA MART\nPAINE 4.50\nTOTAL 4.50"
        val s = ReceiptSections.split(plain)

        assertFalse(s.marked)
        assertEquals(plain, s.items)
        assertEquals(plain, s.footer)
        assertEquals(plain, s.header)
    }

    /** The plain reading-order text above the reconstruction belongs to no section: it is the same
     *  content again, and counting it twice would double every amount a caller sums. */
    @Test
    fun `text before the reconstruction is in no section`() {
        val s = ReceiptSections.split(sectioned)
        listOf(s.header, s.items, s.footer).forEach {
            assertFalse(it.contains("plain reading order"))
        }
        // It is not discarded either — it is the corpus the patterns read best.
        assertTrue(s.plain.contains("plain reading order"))
    }
}
