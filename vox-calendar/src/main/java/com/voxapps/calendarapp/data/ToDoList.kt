package com.voxapps.calendarapp.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A named checklist (e.g. "Mergi la magazin") containing an ordered set of [ToDoItem]s. [layerId] is
 * a plain non-FK column, same convention as [CalendarEntry.layerId] — every list belongs to exactly
 * one layer for color/visibility grouping, though (unlike entries) a list itself never appears on the
 * Month/Week/Day/Year grid; only its items with a due date do, via their own auto-managed
 * [CalendarEntry] (see [ToDoItem.linkedEntryId]). [colorArgb] is assigned from the shared
 * `VoxColorPalette` on creation (same random-or-unused-preset convention as Expenses/Notes
 * categories) and tints the list's card in the to-do list UI.
 *
 * [routineDaysMask] (see [WeekdayMask]) makes the list a recurring routine: on each masked weekday's
 * first midnight (or first app/widget wake after it), every UNDATED item's done flag clears so the
 * routine can be walked again — no history rows, editing the list edits the routine. 0 = an ordinary
 * one-shot list. Dated items are excluded from the reset: a due date makes an item a one-shot
 * deadline, not a routine step. [routineLastResetDay] (epoch day) makes the reset idempotent per
 * local day no matter how many wake paths race to run it.
 */
@Entity(tableName = "todo_lists", indices = [Index("layerId")])
data class ToDoList(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uid: String,
    val title: String,
    val colorArgb: Long,
    @ColumnInfo(name = "layerId") val layerId: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val routineDaysMask: Int = 0,
    val routineLastResetDay: Long = 0
)
