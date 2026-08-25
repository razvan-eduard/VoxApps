package com.voxapps.expenses.domain.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scan prompt now has two shapes, chosen by what the configured engine says it can take. The
 * full one is the prompt that has been tuned against real receipts, so it must not drift while the
 * reduced one is being added — [GoldenScanPromptBuilder] is a copy of the builder as it stood
 * before the split, and the first test here is what pins that.
 */
class ScanPromptVariantTest {

    private val categories = listOf("Groceries", "Transport")
    private val ocr = "MEGA MART\n1 BUC X 33.99\nTOTAL 33.99"

    private fun full(preParsedDate: String? = null, preParsedTime: String? = null) =
        ExpenseScanCleanupPromptBuilder.build(
            ocr, categories, "RON", "en",
            preParsedDate = preParsedDate, preParsedTime = preParsedTime, includeLineItems = true
        )

    private fun headerOnly(preParsedDate: String? = null, preParsedTime: String? = null) =
        ExpenseScanCleanupPromptBuilder.build(
            ocr, categories, "RON", "en",
            preParsedDate = preParsedDate, preParsedTime = preParsedTime, includeLineItems = false
        )

    /** Trailing spaces at line ends are the one difference allowed: the editor strips them on save,
     *  and they cannot change what the model reads. Everything else must match character for
     *  character — this prompt is tuned against real receipts and the split must not drift it. */
    private fun String.ignoringLineEndSpace() = trimEnd().lines().joinToString("\n") { it.trimEnd() }

    @Test
    fun `the full prompt is the prompt that came before the split`() {
        assertEquals(
            GoldenScanPromptBuilder.build(ocr, categories, "RON", "en").ignoringLineEndSpace(),
            full().ignoringLineEndSpace()
        )
        assertEquals(
            GoldenScanPromptBuilder.build(ocr, categories, "RON", "en", "2026-08-15", "12:30")
                .ignoringLineEndSpace(),
            full("2026-08-15", "12:30").ignoringLineEndSpace()
        )
    }

    @Test
    fun `the reduced prompt asks for no items and says so`() {
        val prompt = headerOnly()
        assertFalse("still asks for line items", prompt.contains("line items"))
        assertFalse("still describes unitPrice", prompt.contains("unitPrice"))
        assertFalse("still carries the price rule", prompt.contains("DISTRIBUTIVE"))
        assertFalse("still shows an items array in the response shape", prompt.contains("[{\"name\""))
        // The only mention left is the instruction not to send one.
        assertTrue("does not forbid an items array", prompt.contains("Do NOT return an \"items\" array"))
        assertEquals(1, Regex("\"items\"").findAll(prompt).count())
    }

    /**
     * Everything that is not about items is the same question either way — a header-only run must
     * still produce a complete record, not a degraded one.
     */
    @Test
    fun `the reduced prompt keeps every header instruction`() {
        val prompt = headerOnly()
        listOf(
            "vendor/store name",
            "ING BANK",
            "totalAmount",
            "Groceries, Transport",
            "\"direction\"",
            "OCR text: $ocr"
        ).forEach { assertTrue("reduced prompt lost: $it", prompt.contains(it)) }
    }

    @Test
    fun `the reduced prompt drops items from the bypass instruction too`() {
        val prompt = headerOnly("2026-08-15", "12:30")
        assertTrue(prompt.contains("EXTRACT ONLY the vendor name, bank (if printed), total amount."))
        assertFalse(prompt.contains("and line items"))
    }

    /**
     * The reason the reading runs first: what the page proved never becomes a question.
     *
     * The one exception is a request that also asks for the rows. There the printed total is the
     * anchor the rows are made to sum to — the arithmetic that makes the offline rung real — so it
     * stays in the prompt, stated as known rather than asked for.
     */
    @Test
    fun `a proved figure leaves the question`() {
        val asked = ExpenseScanCleanupPromptBuilder.build(
            ocr, categories, "RON", "en", includeLineItems = false
        )
        assertTrue("with nothing proved it still asks", asked.contains("\"totalAmount\""))
        assertTrue(asked.contains("Use \"RON\" as the currency"))

        val settled = ExpenseScanCleanupPromptBuilder.build(
            ocr, categories, "RON", "en",
            preParsedTotal = 33.99, preParsedCurrency = "RON", includeLineItems = false
        )
        assertTrue(settled.contains("total is already known"))
        assertTrue(settled.contains("currency is already known"))
        assertFalse("the keys leave the shape", settled.contains("\"totalAmount\""))
        assertFalse(settled.contains("\"currency\""))
        assertFalse("and it stops offering a default to echo", settled.contains("Use \"RON\" as the currency"))
    }

    /** With the rows asked for, the total is the anchor — it stays, as a statement. */
    @Test
    fun `a request for rows keeps the total as the figure they must sum to`() {
        val withItems = ExpenseScanCleanupPromptBuilder.build(
            ocr, categories, "RON", "en", preParsedTotal = 33.99, includeLineItems = true
        )
        assertTrue(withItems.contains("own total is 33.99"))
        assertTrue("the rows still need somewhere to go", withItems.contains("\"totalAmount\""))
    }
}
