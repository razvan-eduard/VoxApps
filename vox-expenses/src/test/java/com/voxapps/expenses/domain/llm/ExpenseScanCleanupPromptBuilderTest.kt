package com.voxapps.expenses.domain.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpenseScanCleanupPromptBuilderTest {

    @Test
    fun `includes the raw OCR text, default currency, and language`() {
        val prompt = ExpenseScanCleanupPromptBuilder.build(
            rawText = "MAGAZIN X\n1 BUC X 33.99\nTOTAL 33.99",
            existingCategories = listOf("Mancare", "Transport"),
            defaultCurrency = "RON",
            languageCode = "ro"
        )

        assertTrue(prompt.contains("1 BUC X 33.99"))
        assertTrue(prompt.contains("RON"))
        assertTrue(prompt.contains("\"ro\""))
        assertTrue(prompt.contains("Mancare"))
        assertTrue(prompt.contains("Transport"))
    }

    @Test
    fun `includes the distributive vs cumulative line-item price rule`() {
        val prompt = ExpenseScanCleanupPromptBuilder.build(
            rawText = "text",
            existingCategories = emptyList(),
            defaultCurrency = "RON",
            languageCode = "en"
        )

        assertTrue(prompt.contains("DISTRIBUTIVE"))
        assertTrue(prompt.contains("CUMULATIVE"))
        // The invariant now comes from the shared DistributiveCumulativeRule (see
        // ExpenseParsePromptBuilder, which teaches the identical rule for spoken utterances) rather
        // than being hand-copied text local to this builder.
        assertTrue(prompt.contains(DistributiveCumulativeRule.INVARIANT))
        assertTrue(prompt.contains("does not apply to \"totalAmount\""))
    }

    @Test
    fun `still prefers the printed total and clarifies the line-item rule does not override it`() {
        val prompt = ExpenseScanCleanupPromptBuilder.build(
            rawText = "text",
            existingCategories = emptyList(),
            defaultCurrency = "RON",
            languageCode = "en"
        )

        assertTrue(prompt.contains("ALWAYS prefer the receipt's own printed/stated total"))
        assertTrue(prompt.contains("always prefer the printed total here"))
    }

    @Test
    fun `bypasses date-time extraction when pre-parsed metadata is provided`() {
        val prompt = ExpenseScanCleanupPromptBuilder.build(
            rawText = "text",
            existingCategories = emptyList(),
            defaultCurrency = "RON",
            languageCode = "en",
            preParsedDate = "2026-07-16",
            preParsedTime = "12:30"
        )

        assertTrue(prompt.contains("DO NOT search for, extract, or guess the transaction date or time"))
        assertFalse(prompt.contains("DO NOT suggest any date in the future"))
    }

    @Test
    fun `requests full date-time extraction when pre-parsed metadata is missing`() {
        val prompt = ExpenseScanCleanupPromptBuilder.build(
            rawText = "text",
            existingCategories = emptyList(),
            defaultCurrency = "RON",
            languageCode = "en"
        )

        assertTrue(prompt.contains("DO NOT suggest any date in the future"))
        assertFalse(prompt.contains("DO NOT search for, extract, or guess the transaction date or time"))
    }

    @Test
    fun `asks for JSON-only output with the expected shape`() {
        val prompt = ExpenseScanCleanupPromptBuilder.build("text", emptyList(), "RON", "en")
        assertTrue(prompt.contains("JSON"))
        assertTrue(prompt.contains("no markdown"))
        assertTrue(prompt.contains("\"totalAmount\""))
        assertTrue(prompt.contains("\"items\""))
    }

    @Test
    fun `imageOnly drops the OCR framing and text block, keeping the shared extraction rules`() {
        val prompt = ExpenseScanCleanupPromptBuilder.build(
            rawText = "",
            existingCategories = listOf("Mancare"),
            defaultCurrency = "RON",
            languageCode = "en",
            imageOnly = true
        )

        assertTrue(prompt.contains("no OCR text is available"))
        assertFalse(prompt.contains("OCR text:"))
        assertFalse(prompt.contains("extracted via OCR from a receipt"))
        // Still reuses every shared instruction — this isn't a from-scratch prompt.
        assertTrue(prompt.contains("DISTRIBUTIVE"))
        assertTrue(prompt.contains("CUMULATIVE"))
        assertTrue(prompt.contains("Mancare"))
        assertTrue(prompt.contains("\"items\""))
    }

    @Test
    fun `imageOnly false (default) keeps the original OCR framing and text block`() {
        val prompt = ExpenseScanCleanupPromptBuilder.build(
            rawText = "TOTAL 12.50",
            existingCategories = emptyList(),
            defaultCurrency = "RON",
            languageCode = "en"
        )

        assertTrue(prompt.contains("extracted via OCR from a receipt"))
        assertTrue(prompt.contains("OCR text: TOTAL 12.50"))
        assertFalse(prompt.contains("no OCR text is available"))
    }
}
