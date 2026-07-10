package com.voxapps.expenses.receiver

import com.voxapps.expenses.data.ExpensesRepository
import com.voxapps.expenses.data.preferences.ExpensesSettingsRepository
import com.voxapps.expenses.state.SessionManager
import com.voxapps.ipc.VoxResult

/**
 * The read-command decision, extracted from the BroadcastReceiver so it's unit-testable without
 * Android (mirrors vox-notes' NotesReadResponder). When biometric is required and the session has
 * expired it returns a locked message and **never touches the DB**; otherwise it snapshots expenses.
 */
class ExpensesReadResponder(
    private val settingsRepo: ExpensesSettingsRepository,
    private val sessionManager: SessionManager,
    private val expensesRepo: ExpensesRepository
) {
    suspend fun respond(): VoxResult {
        val settings = settingsRepo.getSnapshot()
        val locked = settings.isBiometricRequired &&
            !sessionManager.isSessionValid(settings.sessionTimeoutMinutes)
        if (locked) return VoxResult(ok = false, text = LOCKED_MESSAGE)

        val text = expensesRepo.expensesSnapshot().joinToString("\n") { expense ->
            val label = expense.title?.takeIf { it.isNotBlank() } ?: expense.vendor
            listOfNotNull(label, "${expense.totalAmount} ${expense.currencyCode}").joinToString(": ")
        }
        return VoxResult(ok = true, text = text)
    }

    companion object {
        const val LOCKED_MESSAGE = "Cheltuielile sunt blocate. Deblochează aplicația."
    }
}
