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
     * The day a month answers with when it becomes the one on screen.
     *
     * Arriving in a month has to land somewhere, and the day-of-month carried over from the month
     * just left is a date nobody chose — it is an artefact of where the last month happened to be
     * standing, and it decides what the agenda below shows. So: the first of the month, except for
     * the month containing [today], which answers with today. Coming back to the present should
     * arrive at the present rather than at the 1st.
     */
    fun dayToLandOn(month: YearMonth, today: LocalDate = LocalDate.now()): LocalDate =
        if (month == YearMonth.from(today)) today else month.atDay(1)

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
