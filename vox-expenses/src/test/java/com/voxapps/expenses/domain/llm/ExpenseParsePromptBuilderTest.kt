package com.voxapps.expenses.domain.llm

import com.voxapps.ipc.VoxSatelliteSchema
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpenseParsePromptBuilderTest {

    @Test
    fun `buildTemplate carries the input placeholder instead of a literal utterance`() {
        val template = ExpenseParsePromptBuilder.buildTemplate(
            existingCategories = listOf("Mancare"), defaultCurrency = "RON", languageCode = "ro"
        )
        assertTrue(template.contains(VoxSatelliteSchema.INPUT_PLACEHOLDER))
        assertTrue(template.contains("Mancare"))
    }

    @Test
    fun `VoxSatelliteSchema buildPrompt substitutes the template's placeholder correctly`() {
        val template = ExpenseParsePromptBuilder.buildTemplate(emptyList(), "RON", "ro")
        val schema = VoxSatelliteSchema(needsExtractionPass = true, promptTemplate = template)
        val prompt = schema.buildPrompt("action: buy\nsubject: 3 apples for 6 lei")
        assertTrue(prompt.contains("action: buy"))
        assertFalse(prompt.contains(VoxSatelliteSchema.INPUT_PLACEHOLDER))
    }

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
