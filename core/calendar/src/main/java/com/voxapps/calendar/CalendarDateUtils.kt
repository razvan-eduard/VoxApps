package com.voxapps.calendar

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Pure date-bucketing logic backing [CalendarView] — no Android/Compose dependency, so it's
 * directly unit-testable (mirrors this repo's `ExpenseFilter`/`NoteFilter` testing story).
 */
object CalendarDateUtils {

    /** Epoch millis -> the calendar day (in [zone]) it falls on. */
    fun millisToLocalDate(millis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    /** The first instant (00:00:00.000) of [date] in [zone], as epoch millis. */
    fun startOfDayMillis(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    /** Every [LocalDate] in [month], ascending (28-31 entries depending on the month/year). */
    fun daysInMonth(month: YearMonth): List<LocalDate> =
        (1..month.lengthOfMonth()).map { month.atDay(it) }

    /**
     * Buckets [items] by calendar day (in [zone]), restricted to [month] and keyed by EVERY day of
     * [month] — days with no matching item still get an entry (empty list), so a caller rendering
     * one row per map entry always shows a row for every day, not just days with content.
     */
    fun <T : CalendarItem> bucketByDay(
        items: List<T>,
        month: YearMonth,
        zone: ZoneId = ZoneId.systemDefault()
    ): Map<LocalDate, List<T>> {
        val byDay = items
            .filter { YearMonth.from(millisToLocalDate(it.dateTimeMillis, zone)) == month }
            .groupBy { millisToLocalDate(it.dateTimeMillis, zone) }
            .mapValues { (_, v) -> v.sortedBy { item -> item.dateTimeMillis } }
        return daysInMonth(month).associateWith { day -> byDay[day].orEmpty() }
    }

    /**
     * The last [count] items (chronologically) from the month immediately before [month] — the
     * "previous month peek" shown grayed-out above the current month's day-list. Returned in
     * ascending (chronological) order.
     */
    fun <T : CalendarItem> lastItemsOfPreviousMonth(
        allItems: List<T>,
        month: YearMonth,
        count: Int = 3,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<T> {
        val previous = month.minusMonths(1)
        return allItems
            .filter { YearMonth.from(millisToLocalDate(it.dateTimeMillis, zone)) == previous }
            .sortedBy { it.dateTimeMillis }
            .takeLast(count)
    }

    /**
     * The first [count] items of the month immediately after [month] — the "next month peek" shown
     * grayed-out below the current month's day-list.
     */
    fun <T : CalendarItem> firstItemsOfNextMonth(
        allItems: List<T>,
        month: YearMonth,
        count: Int = 3,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<T> {
        val next = month.plusMonths(1)
        return allItems
            .filter { YearMonth.from(millisToLocalDate(it.dateTimeMillis, zone)) == next }
            .sortedBy { it.dateTimeMillis }
            .take(count)
    }
}
