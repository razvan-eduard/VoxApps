package com.voxapps.notes.domain.llm

import org.junit.Assert.assertEquals
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

    /** The voice flow sends [NoteScanCleanupPromptBuilder.buildTemplate] to Commander and the scan
     *  flow sends [NoteScanCleanupPromptBuilder.build]; the two must be one question. */
    @Test
    fun `the template is the prompt with the text left out`() {
        val categories = listOf("Cumparaturi", "Sanatate")
        val template = NoteScanCleanupPromptBuilder.buildTemplate(categories, "ro")
        val placeholder = com.voxapps.ipc.VoxSatelliteSchema.INPUT_PLACEHOLDER
        assertEquals(
            NoteScanCleanupPromptBuilder.build("Spray anti tantari 33.99 LEI", categories, "ro"),
            template.replace(placeholder, "Spray anti tantari 33.99 LEI")
        )
        assertEquals("exactly one place for the words", 1, template.split(placeholder).size - 1)
    }
}
