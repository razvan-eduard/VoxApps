package com.voxapps.calendarapp.data

import androidx.room.Embedded
import androidx.room.Relation

/**
 * An entry joined with its full tag list, so cards/screens can render everything without a manual
 * lookup. Room fills [tags] by matching [CalendarEntry.id] -> [CalendarEntryTag.entryId].
 */
data class CalendarEntryWithTags(
    @Embedded val entry: CalendarEntry,
    @Relation(parentColumn = "id", entityColumn = "entryId")
    val tags: List<CalendarEntryTag> = emptyList()
) {
    val tagNames: List<String> get() = tags.map { it.tagName }
}
