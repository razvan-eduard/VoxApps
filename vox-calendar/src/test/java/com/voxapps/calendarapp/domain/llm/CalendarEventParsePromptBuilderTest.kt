package com.voxapps.calendarapp.domain.llm

import com.voxapps.ipc.VoxSatelliteSchema
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CalendarEventParsePromptBuilderTest {

    @Test
    fun `includes today's date and the raw request text`() {
        val prompt = CalendarEventParsePromptBuilder
            .buildTemplate(emptyList(), emptyList(), "en", today = LocalDate.of(2026, 7, 12))
            .replace(VoxSatelliteSchema.INPUT_PLACEHOLDER, "dentist in a week")
        assertTrue(prompt.contains("2026-07-12"))
        assertTrue(prompt.contains("dentist in a week"))
    }

    @Test
    fun `lists existing layers verbatim so the LLM can copy a name exactly`() {
        val prompt = CalendarEventParsePromptBuilder
            .buildTemplate(listOf("Personal", "Work"), emptyList(), "en")
            .replace(VoxSatelliteSchema.INPUT_PLACEHOLDER, "meeting tomorrow")
        assertTrue(prompt.contains("Personal"))
        assertTrue(prompt.contains("Work"))
        assertTrue(prompt.contains("NEVER translate/re-spell"))
    }

    @Test
    fun `asks for JSON-only output with the expected shape`() {
        val prompt = CalendarEventParsePromptBuilder.buildTemplate(emptyList(), emptyList(), "en")
        assertTrue(prompt.contains("JSON"))
        assertTrue(prompt.contains("no markdown"))
        assertTrue(prompt.contains("\"startDate\""))
        assertTrue(prompt.contains("\"EVENT\"|\"TASK\"|\"TODO\""))
    }
}
