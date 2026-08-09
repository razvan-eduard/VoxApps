package com.voxapps.calendarapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class RecurrenceExpanderTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    private fun millis(date: LocalDate): Long =
        ZonedDateTime.of(date, java.time.LocalTime.of(9, 0), zone).toInstant().toEpochMilli()

    private fun entry(
        start: LocalDate,
        frequency: RecurrenceFrequency = RecurrenceFrequency.NONE,
        untilMillis: Long? = null,
        interval: Int = 1
    ) = CalendarEntry(
        id = 1,
        uid = "uid",
        type = CalendarEntryType.EVENT,
        title = "Entry",
        startMillis = millis(start),
        layerId = 1,
        recurrenceFrequency = frequency,
        recurrenceInterval = interval,
        recurrenceUntilMillis = untilMillis,
        createdAt = 0L,
        updatedAt = 0L
    )

    @Test
    fun `non-recurring entry inside the window produces exactly one occurrence`() {
        val e = entry(LocalDate.of(2026, 3, 10))
        val occurrences = RecurrenceExpander.expand(
            e, millis(LocalDate.of(2026, 3, 1)), millis(LocalDate.of(2026, 3, 31)), zone
        )
        assertEquals(1, occurrences.size)
        assertEquals(e.startMillis, occurrences[0].startMillis)
    }

    @Test
    fun `non-recurring entry outside the window produces no occurrences`() {
        val e = entry(LocalDate.of(2026, 1, 10))
        val occurrences = RecurrenceExpander.expand(
            e, millis(LocalDate.of(2026, 3, 1)), millis(LocalDate.of(2026, 3, 31)), zone
        )
        assertTrue(occurrences.isEmpty())
    }

    @Test
    fun `daily recurrence produces one occurrence per day in the window`() {
        val e = entry(LocalDate.of(2026, 3, 1), RecurrenceFrequency.DAILY)
        val occurrences = RecurrenceExpander.expand(
            e, millis(LocalDate.of(2026, 3, 5)), millis(LocalDate.of(2026, 3, 10)), zone
        )
        assertEquals(6, occurrences.size)
        assertEquals(millis(LocalDate.of(2026, 3, 5)), occurrences.first().startMillis)
        assertEquals(millis(LocalDate.of(2026, 3, 10)), occurrences.last().startMillis)
    }

    @Test
    fun `weekly recurrence lands on the same weekday as the original start`() {
        val start = LocalDate.of(2026, 3, 2) // a Monday
        val e = entry(start, RecurrenceFrequency.WEEKLY)
        val occurrences = RecurrenceExpander.expand(
            e, millis(LocalDate.of(2026, 3, 16)), millis(LocalDate.of(2026, 3, 30)), zone
        )
        occurrences.forEach {
            val date = java.time.Instant.ofEpochMilli(it.startMillis).atZone(zone).toLocalDate()
            assertEquals(java.time.DayOfWeek.MONDAY, date.dayOfWeek)
        }
        assertEquals(3, occurrences.size) // Mar 16, 23, 30
    }

    @Test
    fun `monthly recurrence from Jan 31 clamps without compounding drift`() {
        // Jan 31 -> Feb 28 (clamped) -> Mar 31 (NOT Mar 28 - computed from the original Jan 31, not
        // chained off February's clamped date).
        val e = entry(LocalDate.of(2026, 1, 31), RecurrenceFrequency.MONTHLY)
        val occurrences = RecurrenceExpander.expand(
            e, millis(LocalDate.of(2026, 1, 1)), millis(LocalDate.of(2026, 3, 31)), zone
        )
        val dates = occurrences.map { java.time.Instant.ofEpochMilli(it.startMillis).atZone(zone).toLocalDate() }
        assertEquals(
            listOf(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 31)),
            dates
        )
    }

    @Test
    fun `recurrenceUntilMillis stops expansion after the bound`() {
        val e = entry(
            LocalDate.of(2026, 3, 1),
            RecurrenceFrequency.DAILY,
            untilMillis = millis(LocalDate.of(2026, 3, 3))
        )
        val occurrences = RecurrenceExpander.expand(
            e, millis(LocalDate.of(2026, 3, 1)), millis(LocalDate.of(2026, 3, 31)), zone
        )
        assertEquals(3, occurrences.size) // Mar 1, 2, 3
    }

    @Test
    fun `yearly recurrence lands on the same month and day each year`() {
        val e = entry(LocalDate.of(2020, 7, 4), RecurrenceFrequency.YEARLY)
        val occurrences = RecurrenceExpander.expand(
            e, millis(LocalDate.of(2025, 1, 1)), millis(LocalDate.of(2027, 12, 31)), zone
        )
        val dates = occurrences.map { java.time.Instant.ofEpochMilli(it.startMillis).atZone(zone).toLocalDate() }
        assertEquals(
            listOf(LocalDate.of(2025, 7, 4), LocalDate.of(2026, 7, 4), LocalDate.of(2027, 7, 4)),
            dates
        )
    }

    @Test
    fun `every-2-weeks recurrence skips the off weeks`() {
        val start = LocalDate.of(2026, 3, 2) // a Monday
        val e = entry(start, RecurrenceFrequency.WEEKLY, interval = 2)
        val occurrences = RecurrenceExpander.expand(
            e, millis(LocalDate.of(2026, 3, 1)), millis(LocalDate.of(2026, 4, 30)), zone
        )
        val dates = occurrences.map { java.time.Instant.ofEpochMilli(it.startMillis).atZone(zone).toLocalDate() }
        assertEquals(
            listOf(
                LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 16),
                LocalDate.of(2026, 3, 30), LocalDate.of(2026, 4, 13), LocalDate.of(2026, 4, 27)
            ),
            dates
        )
    }

    @Test
    fun `every-3-months recurrence spaces occurrences 3 months apart`() {
        val e = entry(LocalDate.of(2026, 1, 20), RecurrenceFrequency.MONTHLY, interval = 3)
        val occurrences = RecurrenceExpander.expand(
            e, millis(LocalDate.of(2026, 1, 1)), millis(LocalDate.of(2026, 12, 31)), zone
        )
        val dates = occurrences.map { java.time.Instant.ofEpochMilli(it.startMillis).atZone(zone).toLocalDate() }
        assertEquals(
            listOf(LocalDate.of(2026, 1, 20), LocalDate.of(2026, 4, 20), LocalDate.of(2026, 7, 20), LocalDate.of(2026, 10, 20)),
            dates
        )
    }

    @Test
    fun `nextOccurrenceOnOrAfter finds the first occurrence at or past fromMillis`() {
        val e = entry(LocalDate.of(2026, 1, 20), RecurrenceFrequency.MONTHLY)
        val next = RecurrenceExpander.nextOccurrenceOnOrAfter(e, millis(LocalDate.of(2026, 3, 1)), zone)
        assertEquals(millis(LocalDate.of(2026, 3, 20)), next?.startMillis)
    }

    @Test
    fun `nextOccurrenceOnOrAfter returns null once recurrenceUntilMillis is exhausted`() {
        val e = entry(
            LocalDate.of(2026, 1, 20), RecurrenceFrequency.MONTHLY,
            untilMillis = millis(LocalDate.of(2026, 2, 1))
        )
        val next = RecurrenceExpander.nextOccurrenceOnOrAfter(e, millis(LocalDate.of(2026, 3, 1)), zone)
        assertEquals(null, next)
    }

    @Test
    fun `nextOccurrenceOnOrAfter for a non-recurring entry returns null once it has passed`() {
        val e = entry(LocalDate.of(2026, 1, 20))
        assertEquals(null, RecurrenceExpander.nextOccurrenceOnOrAfter(e, millis(LocalDate.of(2026, 2, 1)), zone))
        assertEquals(
            e.startMillis,
            RecurrenceExpander.nextOccurrenceOnOrAfter(e, millis(LocalDate.of(2026, 1, 1)), zone)?.startMillis
        )
    }
}
