package com.voxapps.design.filter

import org.junit.Assert.assertEquals
import org.junit.Test

/** What a narrowed list says about itself. */
class VoxFilterSummaryTest {

    @Test
    fun `nothing in force falls back to the plain name`() {
        assertEquals("All expenses", VoxFilterSummary.of(listOf(null, null), "All expenses"))
        assertEquals("All expenses", VoxFilterSummary.of(emptyList(), "All expenses"))
    }

    @Test
    fun `one filter names itself`() {
        assertEquals("ING", VoxFilterSummary.of(listOf(null, "ING", null), "All expenses"))
    }

    @Test
    fun `several are joined in the order given`() {
        assertEquals(
            "Groceries · ING · Cluj",
            VoxFilterSummary.of(listOf("Groceries", "ING", "Cluj"), "All expenses")
        )
    }

    /** Blanks are as absent as nulls: a filter set to empty text narrows nothing. */
    @Test
    fun `blank parts drop out rather than leaving gaps`() {
        assertEquals("ING", VoxFilterSummary.of(listOf("", "ING", "   "), "All expenses"))
        assertEquals("All expenses", VoxFilterSummary.of(listOf("", "  "), "All expenses"))
    }

    /** Switching one off must not move the others, or the button reads differently every time. */
    @Test
    fun `dropping a part leaves the rest where they were`() {
        assertEquals("A · B · C", VoxFilterSummary.of(listOf("A", "B", "C"), "none"))
        assertEquals("A · C", VoxFilterSummary.of(listOf("A", null, "C"), "none"))
    }
}
