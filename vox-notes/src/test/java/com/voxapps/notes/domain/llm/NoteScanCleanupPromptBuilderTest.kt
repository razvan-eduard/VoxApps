package com.voxapps.notes.domain.llm

import org.junit.Assert.assertTrue
import org.junit.Test

class NoteScanCleanupPromptBuilderTest {

    @Test
    fun `includes OCR text, existing categories, and asks for category+title+text JSON`() {
        val prompt = NoteScanCleanupPromptBuilder.build(
            rawText = "Spray anti tantari 33.99 LEI",
            existingCategories = listOf("Cumparaturi", "Sanatate"),
            languageCode = "ro"
        )
        assertTrue(prompt.contains("Spray anti tantari 33.99 LEI"))
        assertTrue(prompt.contains("Cumparaturi"))
        assertTrue(prompt.contains("Sanatate"))
        assertTrue(prompt.contains("\"category\""))
        assertTrue(prompt.contains("no markdown"))
    }

    @Test
    fun `handles no existing categories`() {
        val prompt = NoteScanCleanupPromptBuilder.build("some text", emptyList(), "en")
        assertTrue(prompt.contains("No categories exist yet."))
    }
}
