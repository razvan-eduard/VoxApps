package com.voxapps.calendarapp.data

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Pure, unit-testable expansion of a (possibly recurring) [CalendarEntry] into concrete occurrence
 * instants within a visible date window (whatever the current Month/Week/Day/Year view needs).
 * Deliberately minimal — no RRULE engine, no materialized per-occurrence rows; editing a recurring
 * entry edits the whole series. No Room/Android dependency.
 */
object RecurrenceExpander {
    data class Occurrence(val startMillis: Long, val endMillis: Long?)

    // Generous enough to cover a multi-year DAILY expansion window (e.g. core:calendar's CalendarView
    // buckets a flat item list per month itself, so Month view pre-expands across a bounded multi-year
    // range up front rather than per-page — see EntryCalendarItem.kt).
    private const val MAX_ITERATIONS = 2000

    fun expand(
        entry: CalendarEntry,
        windowStartMillis: Long,
        windowEndMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): List<Occurrence> {
        val durationMillis = entry.endMillis?.let { it - entry.startMillis }

        if (entry.recurrenceFrequency == RecurrenceFrequency.NONE) {
            return if (occurrenceOverlapsWindow(entry.startMillis, entry.endMillis, windowStartMillis, windowEndMillis)) {
                listOf(Occurrence(entry.startMillis, entry.endMillis))
            } else {
                emptyList()
            }
        }

        val entryStart = ZonedDateTime.ofInstant(Instant.ofEpochMilli(entry.startMillis), zoneId)
        val windowStart = ZonedDateTime.ofInstant(Instant.ofEpochMilli(windowStartMillis), zoneId)
        val until = entry.recurrenceUntilMillis

        // Skip ahead analytically to roughly the first occurrence index that could fall in-or-after
        // the window (with one step of slack for rounding), rather than iterating one-by-one from the
        // original start — a DAILY entry created years ago, viewed in a far-future window, would
        // otherwise take thousands of loop passes. Always computed as an offset from the ORIGINAL
        // start (not by chaining plusMonths/plusYears off a previously-clamped date), so month-end
        // clamping (e.g. Jan 31 -> Feb 28) never compounds into date drift across occurrences.
        var index = (
            when (entry.recurrenceFrequency) {
                RecurrenceFrequency.DAILY -> ChronoUnit.DAYS.between(entryStart, windowStart)
                RecurrenceFrequency.WEEKLY -> ChronoUnit.WEEKS.between(entryStart, windowStart)
                RecurrenceFrequency.MONTHLY -> ChronoUnit.MONTHS.between(entryStart, windowStart)
                RecurrenceFrequency.YEARLY -> ChronoUnit.YEARS.between(entryStart, windowStart)
                RecurrenceFrequency.NONE -> 0L
            }.coerceAtLeast(0L) - 1
            )

        val occurrences = mutableListOf<Occurrence>()
        var iterations = 0
        while (iterations < MAX_ITERATIONS) {
            iterations++
            if (index < 0) {
                index++
                continue
            }
            val occurrenceStart = when (entry.recurrenceFrequency) {
                RecurrenceFrequency.DAILY -> entryStart.plusDays(index)
                RecurrenceFrequency.WEEKLY -> entryStart.plusWeeks(index)
                RecurrenceFrequency.MONTHLY -> entryStart.plusMonths(index)
                RecurrenceFrequency.YEARLY -> entryStart.plusYears(index)
                RecurrenceFrequency.NONE -> entryStart
            }
            val occStartMillis = occurrenceStart.toInstant().toEpochMilli()
            if (occStartMillis > windowEndMillis) break
            if (until != null && occStartMillis > until) break

            val occEndMillis = durationMillis?.let { occStartMillis + it }
            if (occurrenceOverlapsWindow(occStartMillis, occEndMillis, windowStartMillis, windowEndMillis)) {
                occurrences.add(Occurrence(occStartMillis, occEndMillis))
            }
            index++
        }
        return occurrences
    }

    private fun occurrenceOverlapsWindow(start: Long, end: Long?, windowStart: Long, windowEnd: Long): Boolean {
        val effectiveEnd = end ?: start
        return start <= windowEnd && effectiveEnd >= windowStart
    }
}
