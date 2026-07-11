package com.voxapps.expenses.ui

import com.voxapps.calendar.CalendarItem
import com.voxapps.expenses.data.ExpenseWithDetails

/** Adapts [ExpenseWithDetails] to [CalendarItem] for [com.voxapps.calendar.CalendarView] — keeps
 *  `:core:calendar` decoupled from any specific app's Room model. */
@JvmInline
value class ExpenseCalendarItem(val ewd: ExpenseWithDetails) : CalendarItem {
    override val id: Any get() = ewd.expense.id
    override val dateTimeMillis: Long get() = ewd.expense.dateTime
}
