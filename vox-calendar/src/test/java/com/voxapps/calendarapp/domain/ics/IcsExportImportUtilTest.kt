package com.voxapps.calendarapp.domain.ics

import com.voxapps.calendarapp.data.CalendarEntryType
import com.voxapps.calendarapp.data.CalendarRepository
import com.voxapps.calendarapp.data.RecurrenceFrequency
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

class IcsExportImportUtilTest {

    private val icsWithCalendarName = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Test//Test//EN
        X-WR-CALNAME:Public Holidays
        BEGIN:VEVENT
        UID:event-1
        SUMMARY:New Year's Day
        CATEGORIES:Holidays,fun
        DTSTART:20260101T000000Z
        DTEND:20260102T000000Z
        END:VEVENT
        BEGIN:VEVENT
        UID:event-2
        SUMMARY:Independence Day
        DTSTART:20260704T000000Z
        DTEND:20260705T000000Z
        END:VEVENT
        END:VCALENDAR
    """.trimIndent()

    private val icsWithoutCalendarName = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Test//Test//EN
        BEGIN:VEVENT
        UID:event-3
        SUMMARY:Plain event
        DTSTART:20260101T000000Z
        DTEND:20260102T000000Z
        END:VEVENT
        END:VCALENDAR
    """.trimIndent()

    @Test
    fun `readWithSuggestedName extracts X-WR-CALNAME when present`() {
        val result = IcsExportImportUtil.readWithSuggestedName(ByteArrayInputStream(icsWithCalendarName.toByteArray()))
        assertEquals("Public Holidays", result.suggestedName)
        assertEquals(2, result.entries.size)
    }

    @Test
    fun `readWithSuggestedName returns null suggested name when X-WR-CALNAME is absent`() {
        val result = IcsExportImportUtil.readWithSuggestedName(ByteArrayInputStream(icsWithoutCalendarName.toByteArray()))
        assertNull(result.suggestedName)
        assertEquals(1, result.entries.size)
    }

    @Test
    fun `importEntriesIntoLayer assigns every entry to the target layer regardless of its own CATEGORIES`() = runTest {
        val repository = mockk<CalendarRepository>(relaxed = true)

        val parsed = listOf(
            ParsedIcsEntry(
                uid = "uid-a", type = CalendarEntryType.EVENT, title = "A", description = null, location = null,
                startMillis = 1000L, endMillis = 2000L, allDay = false, completed = false,
                recurrenceFrequency = RecurrenceFrequency.NONE, recurrenceInterval = 1, recurrenceUntilMillis = null,
                layerName = "Some Other Category", tags = listOf("x")
            ),
            ParsedIcsEntry(
                uid = "uid-b", type = CalendarEntryType.EVENT, title = "B", description = null, location = null,
                startMillis = 3000L, endMillis = 4000L, allDay = false, completed = false,
                recurrenceFrequency = RecurrenceFrequency.NONE, recurrenceInterval = 1, recurrenceUntilMillis = null,
                layerName = null, tags = emptyList()
            )
        )

        IcsExportImportUtil.importEntriesIntoLayer(repository, parsed, targetLayerId = 42L)

        coVerify(exactly = 1) {
            repository.addEntry(
                uid = "uid-a", type = any(), title = any(), description = any(), location = any(),
                startMillis = any(), endMillis = any(), allDay = any(), completed = any(), isImportant = any(),
                recurrenceFrequency = any(), recurrenceInterval = any(), recurrenceUntilMillis = any(),
                layerId = 42L, tags = any(), reminderOffsetsMinutes = any(), now = any(),
                listId = any(), position = any(), colorArgb = any(), comments = any()
            )
        }
        coVerify(exactly = 1) {
            repository.addEntry(
                uid = "uid-b", type = any(), title = any(), description = any(), location = any(),
                startMillis = any(), endMillis = any(), allDay = any(), completed = any(), isImportant = any(),
                recurrenceFrequency = any(), recurrenceInterval = any(), recurrenceUntilMillis = any(),
                layerId = 42L, tags = any(), reminderOffsetsMinutes = any(), now = any(),
                listId = any(), position = any(), colorArgb = any(), comments = any()
            )
        }
    }
}
