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
        // Non-null: this is only ever called on date-bearing entries (the grid's own query already
        // excludes dateless to-do items — see CalendarEntryDao.observeEntriesWithTags' doc comment —
        // and reminder scheduling only ever fires for a dated entry to begin with).
        val startMillis = entry.startMillis!!
        val durationMillis = entry.endMillis?.let { it - startMillis }

        if (entry.recurrenceFrequency == RecurrenceFrequency.NONE) {
            return if (occurrenceOverlapsWindow(startMillis, entry.endMillis, windowStartMillis, windowEndMillis)) {
                listOf(Occurrence(startMillis, entry.endMillis))
            } else {
                emptyList()
            }
        }

        val entryStart = ZonedDateTime.ofInstant(Instant.ofEpochMilli(startMillis), zoneId)
        val windowStart = ZonedDateTime.ofInstant(Instant.ofEpochMilli(windowStartMillis), zoneId)
        val until = entry.recurrenceUntilMillis
        val interval = entry.recurrenceInterval.coerceAtLeast(1)

        val daysMask = entry.recurrenceDaysMask and WeekdayMask.ALL
        if (entry.recurrenceFrequency == RecurrenceFrequency.WEEKLY && daysMask != 0) {
            return expandWeeklyByDays(
                entryStart, startMillis, durationMillis, daysMask, interval, until,
                windowStartMillis, windowEndMillis, windowStart
            )
        }

        // Skip ahead analytically to roughly the first occurrence index that could fall in-or-after
        // the window (with one step of slack for rounding), rather than iterating one-by-one from the
        // original start — a DAILY entry created years ago, viewed in a far-future window, would
        // otherwise take thousands of loop passes. Always computed as an offset from the ORIGINAL
        // start (not by chaining plusMonths/plusYears off a previously-clamped date), so month-end
        // clamping (e.g. Jan 31 -> Feb 28) never compounds into date drift across occurrences.
        // Divided by [interval] since periodsBetween counts single periods, but occurrences only land
        // every [interval]-th one.
        var index = (
            (when (entry.recurrenceFrequency) {
                RecurrenceFrequency.DAILY -> ChronoUnit.DAYS.between(entryStart, windowStart)
                RecurrenceFrequency.WEEKLY -> ChronoUnit.WEEKS.between(entryStart, windowStart)
                RecurrenceFrequency.MONTHLY -> ChronoUnit.MONTHS.between(entryStart, windowStart)
                RecurrenceFrequency.YEARLY -> ChronoUnit.YEARS.between(entryStart, windowStart)
                RecurrenceFrequency.NONE -> 0L
            } / interval).coerceAtLeast(0L) - 1
            )

        val occurrences = mutableListOf<Occurrence>()
        var iterations = 0
        while (iterations < MAX_ITERATIONS) {
            iterations++
            if (index < 0) {
                index++
                continue
            }
            val step = index * interval
            val occurrenceStart = when (entry.recurrenceFrequency) {
                RecurrenceFrequency.DAILY -> entryStart.plusDays(step)
                RecurrenceFrequency.WEEKLY -> entryStart.plusWeeks(step)
                RecurrenceFrequency.MONTHLY -> entryStart.plusMonths(step)
                RecurrenceFrequency.YEARLY -> entryStart.plusYears(step)
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

    /**
     * WEEKLY with an explicit weekday set ([WeekdayMask]): each active week (per [interval], anchored
     * to the start date's week) yields one occurrence per masked weekday, at the start's time-of-day.
     * Candidates ascend — days ascend within a week, weeks ascend across iterations — so the first
     * candidate past the window (or past `until`) ends the scan. Masked days earlier in the start's
     * own week than the start itself never fire (the series can't begin before its start date).
     */
    private fun expandWeeklyByDays(
        entryStart: ZonedDateTime,
        startMillis: Long,
        durationMillis: Long?,
        daysMask: Int,
        interval: Int,
        until: Long?,
        windowStartMillis: Long,
        windowEndMillis: Long,
        windowStart: ZonedDateTime
    ): List<Occurrence> {
        var index = (ChronoUnit.WEEKS.between(entryStart, windowStart) / interval).coerceAtLeast(0L) - 1
        val occurrences = mutableListOf<Occurrence>()
        var iterations = 0
        while (iterations < MAX_ITERATIONS) {
            iterations++
            if (index < 0) {
                index++
                continue
            }
            val weekBase = entryStart.plusWeeks(index * interval)
            for (day in java.time.DayOfWeek.entries) {
                if (!WeekdayMask.contains(daysMask, day)) continue
                val occurrenceStart = weekBase.plusDays((day.value - weekBase.dayOfWeek.value).toLong())
                val occStartMillis = occurrenceStart.toInstant().toEpochMilli()
                if (occStartMillis < startMillis) continue
                if (occStartMillis > windowEndMillis) return occurrences
                if (until != null && occStartMillis > until) return occurrences
                val occEndMillis = durationMillis?.let { occStartMillis + it }
                if (occurrenceOverlapsWindow(occStartMillis, occEndMillis, windowStartMillis, windowEndMillis)) {
                    occurrences.add(Occurrence(occStartMillis, occEndMillis))
                }
            }
            index++
        }
        return occurrences
    }

    /**
     * The single next occurrence starting at-or-after [fromMillis], or `null` if [entry] is
     * non-recurring and its one-shot [CalendarEntry.startMillis] already passed, or recurrence has
     * been exhausted via [CalendarEntry.recurrenceUntilMillis]. Used by reminder scheduling, which
     * needs one specific future fire point rather than a windowed list.
     */
    fun nextOccurrenceOnOrAfter(
        entry: CalendarEntry,
        fromMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Occurrence? {
        if (entry.recurrenceFrequency == RecurrenceFrequency.NONE) {
            val startMillis = entry.startMillis!!
            return if (startMillis >= fromMillis) Occurrence(startMillis, entry.endMillis) else null
        }
        // A window from fromMillis out far enough to be sure of catching the next occurrence even for
        // the coarsest frequency (YEARLY) at the largest reasonable interval; MAX_ITERATIONS bounds the
        // loop regardless, so this is a generous-but-safe upper bound, not a hard correctness limit.
        val windowEnd = fromMillis + 100L * 366 * 24 * 60 * 60 * 1000L
        return expand(entry, fromMillis, windowEnd, zoneId).minByOrNull { it.startMillis }
    }

    private fun occurrenceOverlapsWindow(start: Long, end: Long?, windowStart: Long, windowEnd: Long): Boolean {
        val effectiveEnd = end ?: start
        return start <= windowEnd && effectiveEnd >= windowStart
    }
}
