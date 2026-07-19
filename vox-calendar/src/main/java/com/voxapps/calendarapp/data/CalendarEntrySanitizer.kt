package com.voxapps.calendarapp.data

import com.voxapps.datahygiene.DirtyField
import com.voxapps.datahygiene.FieldCleaner
import com.voxapps.datahygiene.RecordSanitizer

/**
 * Cleans [CalendarEntry]'s LLM/user-derived string fields. `title` is non-nullable, so garbage/null
 * titles fall back to a fixed placeholder rather than degrading to null (an empty/garbage calendar
 * entry title would otherwise render as blank in every list view).
 */
object CalendarEntrySanitizer : RecordSanitizer<CalendarEntry> {
    private const val UNTITLED_FALLBACK = "Untitled"

    override fun sanitize(record: CalendarEntry): CalendarEntry = record.copy(
        title = FieldCleaner.cleanRequired(record.title, UNTITLED_FALLBACK, "title", recordLabel(record)),
        description = FieldCleaner.clean(record.description, "description", recordLabel(record)),
        location = FieldCleaner.clean(record.location, "location", recordLabel(record))
    )

    override fun dirtyFields(record: CalendarEntry): List<DirtyField> = listOfNotNull(
        FieldCleaner.dirtyValue(record.title)?.let { DirtyField("title", it) },
        FieldCleaner.dirtyValue(record.description)?.let { DirtyField("description", it) },
        FieldCleaner.dirtyValue(record.location)?.let { DirtyField("location", it) }
    )

    private fun recordLabel(record: CalendarEntry) = "CalendarEntry#${record.id}"
}
