package com.voxapps.calendarapp.data

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

    override fun isDirty(record: CalendarEntry): Boolean =
        FieldCleaner.isDirty(record.title) ||
            FieldCleaner.isDirty(record.description) ||
            FieldCleaner.isDirty(record.location)

    private fun recordLabel(record: CalendarEntry) = "CalendarEntry#${record.id}"
}
