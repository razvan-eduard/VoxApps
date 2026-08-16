package com.voxapps.expenses.domain.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cursors, on the document that defeats everything else.
 *
 * The fixture is the verbatim text of one real photograph of a pre-printed supply form. Nothing in
 * it is a row: recognition returned the captions of the totals block as one run — "Total de plata:
 * Sold precedent: Data scadenta:" — and their figures as another, further down, with values from
 * different table rows merged into single stretches of text. The table reconstruction declined
 * outright, so there are no sections and no columns, and every line-based reading has nothing to
 * work with.
 *
 * What the page still has is order, and the figures on it do reconcile once paired correctly. This
 * pins that the pairing is found by rank rather than by proximity, which is the only thing that can
 * work when no figure follows its own caption.
 */
class CursorScannerTest {

    private val formText: String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("scan-form-noreconstruction.txt")) {
            "missing form fixture"
        }.bufferedReader().use { it.readText() }

    private fun shippedFooters(): List<CompiledFooter> {
        val file = listOf(
            java.io.File("src/main/assets/schemas/receipt_templates.json"),
            java.io.File("vox-expenses/src/main/assets/schemas/receipt_templates.json"),
            java.io.File("../vox-expenses/src/main/assets/schemas/receipt_templates.json")
        ).first { it.exists() }
        val parsed = com.google.gson.Gson()
            .fromJson(file.readText(), ReceiptTemplateSchema::class.java)
        return ReceiptTemplates.compiled(parsed).footers
    }

    @Test
    fun `captions printed away from their figures still produce a candidate`() {
        val candidates = CursorScanner.candidates(formText, shippedFooters())

        assertTrue("the cursors found no pairing at all", candidates.isNotEmpty())
    }

    /**
     * The two totals this form actually names are what is due and what was carried into it. It never
     * prints its own charges under a caption, so the third figure is not there to be read — it is
     * derived, and the derivation is only worth anything if the two that were read are right.
     */
    @Test
    fun `the cursors recover the totals the form names`() {
        val candidates = CursorScanner.candidates(formText, shippedFooters())

        val correct = candidates.filter {
            it.grandTotal != null && kotlin.math.abs(it.grandTotal!! - 170.91) < 0.005 &&
                it.previousBalance != null && kotlin.math.abs(it.previousBalance!! - 113.94) < 0.005
        }
        assertTrue(
            "no pairing recovered them; candidates were " +
                candidates.joinToString { "${it.templateId}:prev=${it.previousBalance},due=${it.grandTotal}" },
            correct.isNotEmpty()
        )

        // What the invoice charges on its own follows from the two that were read.
        val derived = InvoiceTotalsReconciler.deriveMissing(
            grandTotal = correct.first().grandTotal,
            invoiceTotal = null,
            previousBalance = correct.first().previousBalance
        )
        assertEquals(56.97, derived!!.value, 0.005)
    }

    /** Nothing is claimed on a text with no captions to walk. */
    @Test
    fun `text without captions yields no candidates`() {
        assertEquals(0, CursorScanner.candidates("no totals here 12.00 34.00", shippedFooters()).size)
    }
}
