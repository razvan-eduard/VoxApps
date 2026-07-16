package com.voxapps.expenses.receiver

import com.voxapps.expenses.data.ExpensesRepository
import com.voxapps.expenses.data.preferences.ExpensesSettingsRepository
import com.voxapps.expenses.state.SessionManager
import com.voxapps.ipc.VoxResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * The read-command decision, extracted from the BroadcastReceiver so it's unit-testable without
 * Android (mirrors vox-notes' NotesReadResponder). When biometric is required and the session has
 * expired it returns a locked message and **never touches the DB**; otherwise it snapshots expenses.
 *
 * When [dateFrom]/[dateTo] are both given (Vox Calendar's day-tap summary), the reply is a compact
 * JSON `{"count": N, "items": [{"title", "timeMillis"}, ...]}` instead of the plain-text format, since
 * the caller needs to parse counts/titles programmatically. Any caller that never sets these keeps
 * getting the original human-readable text unchanged.
 */
class ExpensesReadResponder(
    private val settingsRepo: ExpensesSettingsRepository,
    private val sessionManager: SessionManager,
    private val expensesRepo: ExpensesRepository
) {
    suspend fun respond(dateFrom: Long? = null, dateTo: Long? = null): VoxResult {
        val settings = settingsRepo.getSnapshot()
        
        // Fix: If biometric is NOT required, the record is NEVER locked.
        // Previously, an expired session could block access even if the user turned off biometrics.
        val locked = settings.isBiometricRequired &&
            !sessionManager.isSessionValid(settings.sessionTimeoutMinutes)
            
        if (locked) {
            com.voxapps.logging.Logger.d("ExpensesReadResponder", "Read request BLOCKED (Biometric Lock)")
            return VoxResult(ok = false, text = LOCKED_MESSAGE)
        }

        if (dateFrom != null && dateTo != null) {
            val expenses = expensesRepo.expensesForDateRange(dateFrom, dateTo)
            com.voxapps.logging.Logger.d("ExpensesReadResponder", "Read request SUCCESS (Date Range: $dateFrom - $dateTo, Found: ${expenses.size})")
            val items = JSONArray()
            expenses.forEach { expense ->
                items.put(
                    JSONObject().apply {
                        val label = expense.title?.takeIf { it.isNotBlank() } ?: expense.vendor
                            ?: "${expense.totalAmount} ${expense.currencyCode}"
                        put("title", label)
                        put("timeMillis", expense.dateTime)
                    }
                )
            }
            val json = JSONObject().put("count", expenses.size).put("items", items)
            return VoxResult(ok = true, text = json.toString())
        }

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
