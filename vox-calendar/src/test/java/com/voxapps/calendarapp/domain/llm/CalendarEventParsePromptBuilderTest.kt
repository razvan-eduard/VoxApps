package com.voxapps.calendarapp.domain.llm

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CalendarEventParsePromptBuilderTest {

    @Test
    fun `includes today's date and the raw request text`() {
        val prompt = CalendarEventParsePromptBuilder.build(
            rawText = "dentist in a week",
            existingLayers = emptyList(),
            languageCode = "en",
            today = LocalDate.of(2026, 7, 12)
        )
        assertTrue(prompt.contains("2026-07-12"))
        assertTrue(prompt.contains("dentist in a week"))
    }

    @Test
    fun `lists existing layers verbatim so the LLM can copy a name exactly`() {
        val prompt = CalendarEventParsePromptBuilder.build(
            rawText = "meeting tomorrow",
            existingLayers = listOf("Personal", "Work"),
            languageCode = "en"
        )
        assertTrue(prompt.contains("Personal"))
        assertTrue(prompt.contains("Work"))
        assertTrue(prompt.contains("never invent a new spelling"))
    }

    @Test
    fun `asks for JSON-only output with the expected shape`() {
        val prompt = CalendarEventParsePromptBuilder.build("x", emptyList(), "en")
        assertTrue(prompt.contains("JSON"))
        assertTrue(prompt.contains("no markdown"))
        assertTrue(prompt.contains("\"startDate\""))
        assertTrue(prompt.contains("\"EVENT\"|\"TASK\""))
    }
}
