package com.voxapps.notes.data

import com.voxapps.datahygiene.FieldCleaner
import com.voxapps.datahygiene.RecordSanitizer

/**
 * Cleans [Note]'s LLM/user-derived nullable `title`. `text` is mandatory note content, not a
 * structured metadata field, so it's deliberately out of scope here.
 */
object NoteSanitizer : RecordSanitizer<Note> {
    override fun sanitize(record: Note): Note = record.copy(
        title = FieldCleaner.clean(record.title, "title", recordLabel(record))
    )

    override fun isDirty(record: Note): Boolean = FieldCleaner.isDirty(record.title)

    private fun recordLabel(record: Note) = "Note#${record.id}"
}
