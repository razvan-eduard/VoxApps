package com.voxapps.calendarapp.state

import com.voxapps.calendarapp.data.CalendarEntryWithTags

/**
 * Pure filtering of entries by visible layers + selected tags. Kept out of Room so it's trivially
 * unit-testable (mirrors vox-expenses' ExpenseFilter). Recurrence expansion into concrete per-view
 * occurrences happens separately, in the UI layer, via `RecurrenceExpander` — this only decides which
 * *entries* (still un-expanded) are eligible to appear at all.
 */
object CalendarFilter {
    fun apply(
        entries: List<CalendarEntryWithTags>,
        visibleLayerIds: Set<Long>,
        selectedTags: Set<String>
    ): List<CalendarEntryWithTags> = entries.filter { ewt ->
        ewt.entry.layerId in visibleLayerIds &&
            (selectedTags.isEmpty() || ewt.tagNames.any { it in selectedTags })
    }
}
