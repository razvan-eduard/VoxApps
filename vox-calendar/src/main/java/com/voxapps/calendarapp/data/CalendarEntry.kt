package com.voxapps.calendarapp.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class CalendarEntryType { EVENT, TASK }

/** Deliberately minimal — no RRULE engine, no per-occurrence rows. See [RecurrenceExpander]. */
enum class RecurrenceFrequency { NONE, DAILY, WEEKLY, MONTHLY, YEARLY }

/**
 * A single Event or Task — the one record type this app models (see [CalendarEntryType]). [title] is
 * the only mandatory field. [layerId] is a plain non-FK column (same "SQLite can't ALTER TABLE to add
 * an FK" convention `Note.categoryId`/`Expense.categoryId` document), but unlike those it is
 * non-nullable: every entry belongs to exactly one layer, and deleting a layer reassigns its entries to
 * the default layer at the [CalendarRepository] level rather than orphaning them. [uid] is a stable
 * UUID used as the ICS `UID` so export/import round-trips identify the same entry. [endMillis] is null
 * for a Task with no explicit end; an all-day Event still sets both [startMillis]/[endMillis], snapped
 * to day boundaries. [completed] only has meaning for [CalendarEntryType.TASK].
 */
@Entity(
    tableName = "calendar_entries",
    indices = [Index("layerId"), Index("startMillis")]
)
data class CalendarEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uid: String,
    val type: CalendarEntryType,
    val title: String,
    val description: String? = null,
    val location: String? = null,
    val startMillis: Long,
    val endMillis: Long? = null,
    val allDay: Boolean = false,
    val completed: Boolean = false,
    val recurrenceFrequency: RecurrenceFrequency = RecurrenceFrequency.NONE,
    val recurrenceUntilMillis: Long? = null,
    @ColumnInfo(name = "layerId") val layerId: Long,
    val createdAt: Long,
    val updatedAt: Long
)
