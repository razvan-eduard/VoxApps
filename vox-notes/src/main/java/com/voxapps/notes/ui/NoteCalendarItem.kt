package com.voxapps.notes.ui

import com.voxapps.calendar.CalendarItem
import com.voxapps.notes.data.NoteWithCategory

/** Adapts [NoteWithCategory] to [CalendarItem] for [com.voxapps.calendar.CalendarView] — keeps
 *  `:core:calendar` decoupled from any specific app's Room model. */
@JvmInline
value class NoteCalendarItem(val nwc: NoteWithCategory) : CalendarItem {
    override val id: Any get() = nwc.note.id
    override val dateTimeMillis: Long get() = nwc.note.createdAt
}
