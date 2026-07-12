package com.voxapps.calendarapp.domain.daylink

import android.content.Context
import android.content.Intent
import com.voxapps.ipc.VoxIpc

/**
 * Calendar -> Notes/Expenses day-tap-through: a plain explicit-intent `startActivity`, not the IPC
 * broadcast bus — mirrors vox-expenses' `ExpenseScanRequestSender` (which launches Vision the same
 * way). The target Activity reads [VoxIpc.EXTRA_SELECTED_DATE] and pre-sets its existing date filter
 * (see `NotesActivity`/`ExpensesActivity`'s `onCreate`) — no restructuring of either app's filter UI.
 */
object DayLinkIntentSender {
    private const val NOTES_PACKAGE = "com.voxapps.notes"
    private const val NOTES_ACTIVITY_CLASS = "com.voxapps.notes.NotesActivity"
    private const val EXPENSES_PACKAGE = "com.voxapps.expenses"
    private const val EXPENSES_ACTIVITY_CLASS = "com.voxapps.expenses.ExpensesActivity"

    fun openNotesOnDay(context: Context, dayMillis: Long) {
        context.startActivity(
            Intent().apply {
                setClassName(NOTES_PACKAGE, NOTES_ACTIVITY_CLASS)
                putExtra(VoxIpc.EXTRA_SELECTED_DATE, dayMillis)
            }
        )
    }

    fun openExpensesOnDay(context: Context, dayMillis: Long) {
        context.startActivity(
            Intent().apply {
                setClassName(EXPENSES_PACKAGE, EXPENSES_ACTIVITY_CLASS)
                putExtra(VoxIpc.EXTRA_SELECTED_DATE, dayMillis)
            }
        )
    }
}
