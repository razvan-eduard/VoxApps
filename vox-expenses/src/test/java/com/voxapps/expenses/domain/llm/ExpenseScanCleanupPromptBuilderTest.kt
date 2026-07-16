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
        assertTrue(prompt.contains("NEVER divide it by quantity"))
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
}
