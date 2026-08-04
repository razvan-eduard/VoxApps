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
 */
@Entity(tableName = "todo_lists", indices = [Index("layerId")])
data class ToDoList(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uid: String,
    val title: String,
    val colorArgb: Long,
    @ColumnInfo(name = "layerId") val layerId: Long,
    val createdAt: Long,
    val updatedAt: Long
)
