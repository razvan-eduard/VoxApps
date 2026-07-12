package com.voxapps.calendarapp.ui

import com.voxapps.calendar.CalendarItem
import com.voxapps.calendarapp.data.CalendarEntryWithTags
import com.voxapps.calendarapp.data.RecurrenceExpander

/**
 * Adapts one occurrence of a (possibly recurring) [CalendarEntryWithTags] to [CalendarItem] for
 * [com.voxapps.calendar.CalendarView] — keeps `:core:calendar` decoupled from any specific app's Room
 * model. [occurrenceStartMillis] differs from the underlying entry's own `startMillis` for anything
 * after the first occurrence of a recurring entry, so [id] includes it to stay unique per occurrence.
 */
data class EntryCalendarItem(
    val entryWithTags: CalendarEntryWithTags,
    val occurrenceStartMillis: Long,
    val occurrenceEndMillis: Long?
) : CalendarItem {
    override val id: Any get() = "${entryWithTags.entry.id}_$occurrenceStartMillis"
    override val dateTimeMillis: Long get() = occurrenceStartMillis
}

/**
 * Expands every entry into its occurrences within [windowStartMillis]..[windowEndMillis], adapting
 * each into an [EntryCalendarItem]. `core:calendar`'s `CalendarView` buckets a flat item list per month
 * itself rather than exposing a "currently visible window" hook, so recurring entries are pre-expanded
 * across this bounded window up front rather than per-page.
 */
fun List<CalendarEntryWithTags>.toCalendarItems(
    windowStartMillis: Long,
    windowEndMillis: Long
): List<EntryCalendarItem> = flatMap { ewt ->
    RecurrenceExpander.expand(ewt.entry, windowStartMillis, windowEndMillis).map { occurrence ->
        EntryCalendarItem(ewt, occurrence.startMillis, occurrence.endMillis)
    }
}
