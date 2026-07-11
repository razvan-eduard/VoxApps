package com.voxapps.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

private data class FakeItem(override val id: Any, override val dateTimeMillis: Long) : CalendarItem

private val ZONE = ZoneId.of("UTC")

private fun millisOf(date: LocalDate): Long = CalendarDateUtils.startOfDayMillis(date, ZONE)

class CalendarDateUtilsTest {

    @Test
    fun `daysInMonth returns every day ascending`() {
        val month = YearMonth.of(2026, 2) // Feb 2026 (not a leap year -> 28 days)
        val days = CalendarDateUtils.daysInMonth(month)
        assertEquals(28, days.size)
        assertEquals(LocalDate.of(2026, 2, 1), days.first())
        assertEquals(LocalDate.of(2026, 2, 28), days.last())
    }

    @Test
    fun `bucketByDay includes empty days`() {
        val month = YearMonth.of(2026, 3)
        val items = listOf(FakeItem(1, millisOf(LocalDate.of(2026, 3, 5))))
        val buckets = CalendarDateUtils.bucketByDay(items, month, ZONE)

        assertEquals(31, buckets.size)
        assertEquals(1, buckets[LocalDate.of(2026, 3, 5)]?.size)
        assertTrue(buckets[LocalDate.of(2026, 3, 1)]!!.isEmpty())
    }

    @Test
    fun `bucketByDay excludes items outside the month`() {
        val month = YearMonth.of(2026, 3)
        val items = listOf(
            FakeItem(1, millisOf(LocalDate.of(2026, 2, 28))),
            FakeItem(2, millisOf(LocalDate.of(2026, 3, 15))),
            FakeItem(3, millisOf(LocalDate.of(2026, 4, 1)))
        )
        val buckets = CalendarDateUtils.bucketByDay(items, month, ZONE)
        val allBucketed = buckets.values.flatten()
        assertEquals(1, allBucketed.size)
        assertEquals(2, allBucketed.first().id)
    }

    @Test
    fun `lastItemsOfPreviousMonth returns the tail end chronologically`() {
        val month = YearMonth.of(2026, 4)
        val items = (1..5).map { day -> FakeItem(day, millisOf(LocalDate.of(2026, 3, day * 5))) }
        val peek = CalendarDateUtils.lastItemsOfPreviousMonth(items, month, count = 3, zone = ZONE)

        assertEquals(3, peek.size)
        assertEquals(listOf(3, 4, 5), peek.map { it.id })
    }

    @Test
    fun `firstItemsOfNextMonth returns the head chronologically`() {
        val month = YearMonth.of(2026, 4)
        val items = (1..5).map { day -> FakeItem(day, millisOf(LocalDate.of(2026, 5, day * 5))) }
        val peek = CalendarDateUtils.firstItemsOfNextMonth(items, month, count = 3, zone = ZONE)

        assertEquals(3, peek.size)
        assertEquals(listOf(1, 2, 3), peek.map { it.id })
    }

    @Test
    fun `peek windows are empty when adjacent month has no items`() {
        val month = YearMonth.of(2026, 6)
        val items = listOf(FakeItem(1, millisOf(LocalDate.of(2026, 6, 10))))

        assertTrue(CalendarDateUtils.lastItemsOfPreviousMonth(items, month, zone = ZONE).isEmpty())
        assertTrue(CalendarDateUtils.firstItemsOfNextMonth(items, month, zone = ZONE).isEmpty())
    }
}
