package com.voxapps.expenses.domain.llm

import org.junit.Assert.assertTrue
import org.junit.Test

class ExpenseParsePromptBuilderTest {

    @Test
    fun `includes the raw text, default currency, and language`() {
        val prompt = ExpenseParsePromptBuilder.build(
            rawText = "am cumparat 3 paini de la magazin cu 10 lei",
            existingCategories = listOf("Mancare", "Transport"),
            defaultCurrency = "RON",
            languageCode = "ro"
        )

        assertTrue(prompt.contains("am cumparat 3 paini de la magazin cu 10 lei"))
        assertTrue(prompt.contains("RON"))
        assertTrue(prompt.contains("\"ro\""))
        assertTrue(prompt.contains("Mancare"))
        assertTrue(prompt.contains("Transport"))
    }

    @Test
    fun `mentions no categories exist when the list is empty`() {
        val prompt = ExpenseParsePromptBuilder.build("text", emptyList(), "RON", "en")
        assertTrue(prompt.contains("No categories exist yet"))
    }

    @Test
    fun `asks for JSON-only output with the expected shape`() {
        val prompt = ExpenseParsePromptBuilder.build("text", emptyList(), "RON", "en")
        assertTrue(prompt.contains("JSON"))
        assertTrue(prompt.contains("no markdown"))
        assertTrue(prompt.contains("\"totalAmount\""))
        assertTrue(prompt.contains("\"items\""))
    }
}
