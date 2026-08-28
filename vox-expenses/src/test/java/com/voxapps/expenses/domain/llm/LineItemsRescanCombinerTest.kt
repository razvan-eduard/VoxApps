package com.voxapps.expenses.domain.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LineItemsRescanCombinerTest {

    @Test
    fun `a single non-blank page is returned verbatim`() {
        assertEquals("TOTAL 51,33", LineItemsRescanCombiner.combinePageTexts(listOf("TOTAL 51,33")))
    }

    @Test
    fun `several pages join under their own page numbers`() {
        assertEquals(
            "--- Page 1 ---\nfirst\n\n--- Page 2 ---\nsecond",
            LineItemsRescanCombiner.combinePageTexts(listOf("first", "second"))
        )
    }

    @Test
    fun `a blank page is skipped but keeps its neighbours' numbering`() {
        assertEquals(
            "--- Page 1 ---\nfirst\n\n--- Page 3 ---\nthird",
            LineItemsRescanCombiner.combinePageTexts(listOf("first", " ", "third"))
        )
    }

    @Test
    fun `one surviving page of several is still returned verbatim`() {
        assertEquals("only", LineItemsRescanCombiner.combinePageTexts(listOf("", "only", "")))
    }

    @Test
    fun `no text at all is null`() {
        assertNull(LineItemsRescanCombiner.combinePageTexts(emptyList()))
        assertNull(LineItemsRescanCombiner.combinePageTexts(listOf("", "  ")))
    }

    @Test
    fun `ocrEligible drops PDFs case-insensitively and keeps order`() {
        assertEquals(
            listOf("att_a.jpg", "att_c.png"),
            LineItemsRescanCombiner.ocrEligible(listOf("att_a.jpg", "att_b.pdf", "att_c.png", "att_d.PDF"))
        )
        assertEquals(emptyList<String>(), LineItemsRescanCombiner.ocrEligible(listOf("att_b.pdf")))
    }
}
