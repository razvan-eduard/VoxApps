package com.voxapps.calendarapp.data

/**
 * One checkable step within a [ToDoList] (e.g. "cumpara paine") — a plain view-model, NOT a Room
 * entity: it's a projection of a [CalendarEntry] row with [CalendarEntry.listId] set, mapped by
 * [ToDoRepository]. There is no separate `todo_items` table (retired in DB migration 9->10) — a
 * to-do item and its due-date/reminder are the SAME row, not a checklist row kept in sync with a
 * "shadow" calendar entry. [id] is the underlying [CalendarEntry.id]. [colorArgb] is the item's own
 * per-item chip color, independent of the parent list's color, assigned from the shared
 * `VoxColorPalette` on creation.
 */
data class ToDoItem(
    val id: Long,
    val listId: Long,
    val text: String,
    val position: Int = 0,
    val colorArgb: Long,
    val dueMillis: Long? = null,
    val done: Boolean = false,
    val isImportant: Boolean = false,
    val comments: String? = null,
    val location: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)
