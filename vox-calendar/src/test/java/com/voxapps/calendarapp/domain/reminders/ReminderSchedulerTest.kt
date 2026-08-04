package com.voxapps.calendarapp.domain.reminders

import com.voxapps.calendarapp.data.CalendarEntry
import com.voxapps.calendarapp.data.CalendarEntryType
import com.voxapps.calendarapp.data.CalendarReminder
import com.voxapps.calendarapp.data.RecurrenceFrequency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ReminderSchedulerTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private fun millis(date: LocalDate): Long = ZonedDateTime.of(date, LocalTime.of(9, 0), zone).toInstant().toEpochMilli()

    private fun entry(
        startMillis: Long,
        frequency: RecurrenceFrequency = RecurrenceFrequency.NONE,
        untilMillis: Long? = null
    ) = CalendarEntry(
        id = 1,
        uid = "uid-1",
        type = CalendarEntryType.EVENT,
        title = "Dentist appointment",
        startMillis = startMillis,
        layerId = 1,
        recurrenceFrequency = frequency,
        recurrenceUntilMillis = untilMillis,
        createdAt = 0L,
        updatedAt = 0L
    )

    @Test
    fun `zero offset triggers exactly at entry start time`() {
        val e = entry(startMillis = 1_000_000L)
        val reminder = CalendarReminder(entryId = e.id, offsetMinutesBefore = 0)
        assertEquals(1_000_000L, ReminderScheduler.triggerAtMillis(e, reminder, fromMillis = 0L))
    }

    @Test
    fun `offset subtracts minutes-before as milliseconds from entry start`() {
        val e = entry(startMillis = 1_000_000L)
        val reminder = CalendarReminder(entryId = e.id, offsetMinutesBefore = 15)
        assertEquals(1_000_000L - 15 * 60_000L, ReminderScheduler.triggerAtMillis(e, reminder, fromMillis = 0L))
    }

    @Test
    fun `one day offset subtracts a full day in milliseconds`() {
        val e = entry(startMillis = 100_000_000L)
        val reminder = CalendarReminder(entryId = e.id, offsetMinutesBefore = 1440)
        assertEquals(100_000_000L - 1440 * 60_000L, ReminderScheduler.triggerAtMillis(e, reminder, fromMillis = 0L))
    }

    @Test
    fun `recurring entry triggers against the next occurrence on or after fromMillis`() {
        val e = entry(startMillis = millis(LocalDate.of(2026, 1, 20)), frequency = RecurrenceFrequency.MONTHLY)
        val reminder = CalendarReminder(entryId = e.id, offsetMinutesBefore = 60)
        val trigger = ReminderScheduler.triggerAtMillis(e, reminder, fromMillis = millis(LocalDate.of(2026, 3, 1)))
        assertEquals(millis(LocalDate.of(2026, 3, 20)) - 60 * 60_000L, trigger)
    }

    @Test
    fun `recurring entry past recurrenceUntilMillis has no trigger`() {
        val e = entry(
            startMillis = millis(LocalDate.of(2026, 1, 20)),
            frequency = RecurrenceFrequency.MONTHLY,
            untilMillis = millis(LocalDate.of(2026, 2, 1))
        )
        val reminder = CalendarReminder(entryId = e.id, offsetMinutesBefore = 60)
        val trigger = ReminderScheduler.triggerAtMillis(e, reminder, fromMillis = millis(LocalDate.of(2026, 3, 1)))
        assertNull(trigger)
    }
}
