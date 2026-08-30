package com.voxapps.calendarapp.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class CalendarEntryType { EVENT, TASK }

/** Deliberately minimal — no RRULE engine, no per-occurrence rows. See [RecurrenceExpander]. */
enum class RecurrenceFrequency { NONE, DAILY, WEEKLY, MONTHLY, YEARLY }

/**
 * A single Event, Task, or to-do checklist item — the one record type this app models (see
 * [CalendarEntryType]). [title] is the only mandatory field. [layerId] is a plain non-FK column (same
 * "SQLite can't ALTER TABLE to add an FK" convention `Note.categoryId`/`Expense.categoryId` document),
 * but unlike those it is non-nullable: every entry belongs to exactly one layer, and deleting a layer
 * reassigns its entries to the default layer at the [CalendarRepository] level rather than orphaning
 * them. [uid] is a stable UUID used as the ICS `UID` so export/import round-trips identify the same
 * entry. [endMillis] is null for a Task with no explicit end; an all-day Event still sets both
 * [startMillis]/[endMillis], snapped to day boundaries. [startMillis] itself is nullable only because
 * a to-do checklist item ([listId] != null) frequently has no due date at all — a plain Event/Task
 * always has one. [completed] only has meaning for [CalendarEntryType.TASK] and to-do items.
 * [recurrenceInterval] is the step count between occurrences (e.g. 2 + WEEKLY = every 2 weeks); 1 is
 * the original every-single-period behavior and is the default for rows created before this field
 * existed. [recurrenceDaysMask] (see [WeekdayMask]) narrows a WEEKLY recurrence to an explicit set
 * of weekdays (e.g. Mon–Fri); 0 keeps the original single-weekday-of-the-start-date behavior and is
 * ignored for every other frequency.
 *
 * [listId]/[position]/[isImportant]/[comments]/[colorArgb] are the to-do-specific fields (null/default
 * for a plain Event/Task): a row with [listId] != null is a checklist item belonging to that
 * `todo_lists` row — [position] orders it within the list, [colorArgb] is its own per-item chip color
 * (independent of the layer's color, unlike a plain entry), [comments] is a free-text note. Except for
 * [colorArgb] (a to-do-only visual), these fields are exposed generally — [isImportant] in particular
 * is just as meaningful on an ordinary Event/Task as on a to-do item.
 *
 * [individualReminderOffsetsMinutes] (see [ReminderOffsetsCodec]) is this entry's own reminder choice,
 * kept durably even while its calendar's [CalendarLayer.reminderOffsetsMinutes] is overriding what's
 * actually scheduled — see [CalendarRepository.effectiveOffsetsFor]. Never read directly for
 * scheduling; only the effective (calendar-overrides-item) result ever reaches [CalendarReminder].
 */
@Entity(
    tableName = "calendar_entries",
    indices = [Index("layerId"), Index("startMillis"), Index("listId")]
)
data class CalendarEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uid: String,
    val type: CalendarEntryType,
    val title: String,
    val description: String? = null,
    val location: String? = null,
    val startMillis: Long?,
    val endMillis: Long? = null,
    val allDay: Boolean = false,
    val completed: Boolean = false,
    val recurrenceFrequency: RecurrenceFrequency = RecurrenceFrequency.NONE,
    val recurrenceInterval: Int = 1,
    val recurrenceUntilMillis: Long? = null,
    val recurrenceDaysMask: Int = 0,
    @ColumnInfo(name = "layerId") val layerId: Long,
    val isImportant: Boolean = false,
    val comments: String? = null,
    @ColumnInfo(name = "listId") val listId: Long? = null,
    val position: Int = 0,
    val colorArgb: Long? = null,
    /**
     * Which paired device this record arrived from, or null for one made here.
     *
     * Stamped exactly once, when a peer-to-peer sync merge INSERTS the row, and never rewritten —
     * an update from the peer changes what the record says, not where it came from, and the stamp
     * itself never travels on the sync wire. [originDeviceName] is the peer's self-declared display
     * name at stamp time, denormalized so lists and filters need no registry lookup.
     */
    val originDeviceId: String? = null,
    val originDeviceName: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val individualReminderOffsetsMinutes: String? = null
)
