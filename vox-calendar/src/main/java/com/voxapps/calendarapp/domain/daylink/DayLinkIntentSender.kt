package com.voxapps.calendarapp.domain.daylink

import android.content.Context
import android.content.Intent
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxIpc.EXPENSES_PACKAGE
import com.voxapps.ipc.VoxIpc.NOTES_PACKAGE

/**
 * Calendar -> Notes/Expenses day-tap-through: a plain explicit-intent `startActivity`, not the IPC
 * broadcast bus — mirrors vox-expenses' `ExpenseScanRequestSender` (which launches Vision the same
 * way). The target Activity reads [VoxIpc.EXTRA_SELECTED_DATE] and pre-sets its existing date filter
 * (see `NotesActivity`/`ExpensesActivity`'s `onCreate`) — no restructuring of either app's filter UI.
 */
object DayLinkIntentSender {
    private const val NOTES_ACTIVITY_CLASS = com.voxapps.ipc.VoxIpc.NOTES_ACTIVITY_CLASS
    private const val EXPENSES_ACTIVITY_CLASS = com.voxapps.ipc.VoxIpc.EXPENSES_ACTIVITY_CLASS

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

    /** Jumps straight into a specific note's editor — the same [VoxIpc.EXTRA_EDIT_NOTE_ID] extra
     *  NotesWidget's own rows use, just sent cross-app instead of in-process. */
    fun openNoteForEdit(context: Context, noteId: Long) {
        context.startActivity(
            Intent().apply {
                setClassName(NOTES_PACKAGE, NOTES_ACTIVITY_CLASS)
                putExtra(VoxIpc.EXTRA_EDIT_NOTE_ID, noteId)
            }
        )
    }

    /** Jumps straight into a specific expense's editor — the same [VoxIpc.EXTRA_EXPENSE_ID] extra
     *  ExpensesWidget's own rows use, just sent cross-app instead of in-process. */
    fun openExpenseForEdit(context: Context, expenseId: Long) {
        context.startActivity(
            Intent().apply {
                setClassName(EXPENSES_PACKAGE, EXPENSES_ACTIVITY_CLASS)
                putExtra(VoxIpc.EXTRA_EXPENSE_ID, expenseId)
            }
        )
    }
}
