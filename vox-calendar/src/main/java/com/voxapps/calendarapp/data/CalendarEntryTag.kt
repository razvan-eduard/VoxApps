package com.voxapps.calendarapp.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A flat, cross-cutting tag (e.g. "Medical", "Bills") attached to a [CalendarEntry] — independent of
 * [CalendarLayer], so the same tag can apply to entries across different layers. `CASCADE` delete:
 * removing an entry removes its tags with it. Mirrors `ExpenseLineItem`'s FK-child-table shape — the
 * established precedent for "one-to-many attached to a record" in this codebase's Room usage (there is
 * no comma-joined-column precedent anywhere), which also makes `DISTINCT tagName` queryable for the
 * sidebar's tag-filter chips without loading and re-parsing every entry.
 */
@Entity(
    tableName = "calendar_entry_tags",
    foreignKeys = [
        ForeignKey(
            entity = CalendarEntry::class,
            parentColumns = ["id"],
            childColumns = ["entryId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("entryId"), Index("tagName")]
)
data class CalendarEntryTag(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "entryId") val entryId: Long,
    val tagName: String
)
