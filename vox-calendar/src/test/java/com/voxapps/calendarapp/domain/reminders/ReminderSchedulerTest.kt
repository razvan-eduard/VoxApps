package com.voxapps.calendarapp.domain.reminders

import com.voxapps.calendarapp.data.CalendarEntry
import com.voxapps.calendarapp.data.CalendarEntryType
import com.voxapps.calendarapp.data.CalendarReminder
import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderSchedulerTest {

    private fun entry(startMillis: Long) = CalendarEntry(
        id = 1,
        uid = "uid-1",
        type = CalendarEntryType.EVENT,
        title = "Dentist appointment",
        startMillis = startMillis,
        layerId = 1,
        createdAt = 0L,
        updatedAt = 0L
    )

    @Test
    fun `zero offset triggers exactly at entry start time`() {
        val e = entry(startMillis = 1_000_000L)
        val reminder = CalendarReminder(entryId = e.id, offsetMinutesBefore = 0)
        assertEquals(1_000_000L, ReminderScheduler.triggerAtMillis(e, reminder))
    }

    @Test
    fun `offset subtracts minutes-before as milliseconds from entry start`() {
        val e = entry(startMillis = 1_000_000L)
        val reminder = CalendarReminder(entryId = e.id, offsetMinutesBefore = 15)
        assertEquals(1_000_000L - 15 * 60_000L, ReminderScheduler.triggerAtMillis(e, reminder))
    }

    @Test
    fun `one day offset subtracts a full day in milliseconds`() {
        val e = entry(startMillis = 100_000_000L)
        val reminder = CalendarReminder(entryId = e.id, offsetMinutesBefore = 1440)
        assertEquals(100_000_000L - 1440 * 60_000L, ReminderScheduler.triggerAtMillis(e, reminder))
    }
}
