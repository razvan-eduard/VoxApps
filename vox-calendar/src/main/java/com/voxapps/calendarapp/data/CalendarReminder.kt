package com.voxapps.calendarapp.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single reminder attached to a [CalendarEntry]. [entryId] is a plain, non-FK column — same
 * convention as [CalendarEntry.layerId] (SQLite can't ALTER TABLE to add an FK) — cascade cleanup
 * on entry delete is done manually in CalendarRepository, mirroring how attachments are cleaned up.
 *
 * v1 only supports non-recurring entries; [entryId] always points at a standalone entry, never a
 * specific occurrence of a recurring one.
 */
@Entity(tableName = "reminders", indices = [Index("entryId")])
data class CalendarReminder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entryId: Long,
    /** Minutes before [CalendarEntry.startMillis] this reminder should fire; 0 = at start time. */
    val offsetMinutesBefore: Int
)
