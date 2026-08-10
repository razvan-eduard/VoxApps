package com.voxapps.expenses.receiver

import com.voxapps.expenses.data.ExpensesRepository
import com.voxapps.expenses.data.preferences.ExpensesSettingsRepository
import com.voxapps.expenses.state.SessionManager
import com.voxapps.ipc.VoxResult
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/**
 * The read-command decision, extracted from the BroadcastReceiver so it's unit-testable without
 * Android (mirrors vox-notes' NotesReadResponder). When biometric is required and the session has
 * expired it returns a locked message and **never touches the DB**; otherwise it snapshots expenses.
 *
 * When [dateFrom]/[dateTo] are both given (Vox Calendar's day-tap summary), the reply is a compact
 * JSON `{"count": N, "items": [{"title", "timeMillis", "id", "colorArgb"}, ...]}` instead of the
 * plain-text format, since the caller needs to render tappable, color-tinted rows (matching the
 * home-screen widget's look) rather than just display text — "id" lets it deep-link straight into
 * this expense's editor (see [com.voxapps.ipc.VoxIpc.EXTRA_EXPENSE_ID]), "colorArgb" (omitted when
 * uncategorized) is the expense's category color. Any caller that never sets [dateFrom]/[dateTo]
 * keeps getting the original human-readable text unchanged.
 */
class ExpensesReadResponder(
    private val settingsRepo: ExpensesSettingsRepository,
    private val sessionManager: SessionManager,
    private val expensesRepo: ExpensesRepository,
    private val lockedMessage: String
) {
    suspend fun respond(dateFrom: Long? = null, dateTo: Long? = null): VoxResult {
        val settings = settingsRepo.getSnapshot()
        
        // Fix: If biometric is NOT required, the record is NEVER locked.
        // Previously, an expired session could block access even if the user turned off biometrics.
        val locked = settings.isBiometricRequired &&
            !sessionManager.isSessionValid(settings.sessionTimeoutMinutes)
            
        if (locked) {
            com.voxapps.logging.Logger.d("ExpensesReadResponder", "Read request BLOCKED (Biometric Lock)")
            return VoxResult(ok = false, text = lockedMessage)
        }

        if (dateFrom != null && dateTo != null) {
            val expenses = expensesRepo.expensesForDateRange(dateFrom, dateTo)
            com.voxapps.logging.Logger.d("ExpensesReadResponder", "Read request SUCCESS (Date Range: $dateFrom - $dateTo, Found: ${expenses.size})")
            val colorByCategoryId = expensesRepo.categories.first().associate { it.id to it.colorArgb }
            val items = JSONArray()
            expenses.forEach { expense ->
                items.put(
                    JSONObject().apply {
                        val label = expense.title?.takeIf { it.isNotBlank() } ?: expense.vendor
                            ?: "${expense.totalAmount} ${expense.currencyCode}"
                        put("title", label)
                        put("timeMillis", expense.dateTime)
                        put("id", expense.id)
                        expense.categoryId?.let { colorByCategoryId[it] }?.let { put("colorArgb", it) }
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
}
