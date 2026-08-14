package com.voxapps.expenses.domain.llm

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.voxapps.expenses.data.TransactionDirection
import com.voxapps.expenses.data.preferences.DataStoreProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/** One notification-derived expense awaiting review — never created until the user approves it. */
data class PendingNotificationExpense(
    val id: Long,
    val title: String?,
    val totalAmount: Double,
    val currency: String,
    val vendor: String?,
    val category: String?,
    val capturedAt: Long,
    /** Set deterministically when the notification came from a starred (banking) source app —
     *  see [com.voxapps.expenses.receiver.PaymentNotificationListenerService]. */
    val bank: String? = null,
    val direction: TransactionDirection = TransactionDirection.OUTGOING,
    /** The notification's template identity — approving this entry confirms the direction against
     *  it; see [TemplateDirectionMemory]. */
    val templateHash: String? = null
)

/**
 * Holds *pending* expenses captured from payment-app notifications, awaiting individual user review
 * (mirrors the pending-review pattern of [PendingCategoryMergeRepository]/
 * [ExpenseDeduplicationRepository], but each entry is reviewed/approved on its own — there's no
 * "group" concept here). This is the strictest-gated of the three input channels: nothing here was
 * ever a deliberate user action, so nothing is ever auto-created.
 */
class PendingNotificationExpenseRepository(context: Context) {

    private val dataStore = DataStoreProvider.get(context)

    private object Keys {
        val PENDING_ENTRIES = stringPreferencesKey("pending_notification_expenses")
    }

    val pendingFlow: Flow<List<PendingNotificationExpense>> = dataStore.data.map { prefs ->
        prefs[Keys.PENDING_ENTRIES]?.let { decode(it) } ?: emptyList()
    }

    suspend fun addPending(entry: PendingNotificationExpense) {
        dataStore.edit {
            val current = it[Keys.PENDING_ENTRIES]?.let { json -> decode(json) } ?: emptyList()
            it[Keys.PENDING_ENTRIES] = encode(current + entry)
        }
    }

    /** Removes one or more entries by id — used for both "approve" (caller creates the Expense
     *  first) and "dismiss" (caller just discards), since the pending-list bookkeeping is identical. */
    suspend fun removePending(ids: Set<Long>) {
        dataStore.edit {
            val current = it[Keys.PENDING_ENTRIES]?.let { json -> decode(json) } ?: emptyList()
            it[Keys.PENDING_ENTRIES] = encode(current.filter { entry -> entry.id !in ids })
        }
    }

    suspend fun clearAll() {
        dataStore.edit { it.remove(Keys.PENDING_ENTRIES) }
    }

    suspend fun snapshot(): List<PendingNotificationExpense> = pendingFlow.first()

    private fun encode(entries: List<PendingNotificationExpense>): String {
        val array = JSONArray()
        for (e in entries) {
            val o = JSONObject()
            o.put("id", e.id)
            e.title?.let { o.put("title", it) }
            o.put("totalAmount", e.totalAmount)
            o.put("currency", e.currency)
            e.vendor?.let { o.put("vendor", it) }
            e.category?.let { o.put("category", it) }
            e.bank?.let { o.put("bank", it) }
            o.put("direction", e.direction.toJsonValue())
            o.put("capturedAt", e.capturedAt)
            e.templateHash?.let { o.put("templateHash", it) }
            array.put(o)
        }
        return array.toString()
    }

    private fun decode(json: String): List<PendingNotificationExpense> = try {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            PendingNotificationExpense(
                id = o.optLong("id"),
                title = if (o.has("title")) o.optString("title") else null,
                totalAmount = o.optDouble("totalAmount"),
                currency = o.optString("currency"),
                vendor = if (o.has("vendor")) o.optString("vendor") else null,
                category = if (o.has("category")) o.optString("category") else null,
                bank = if (o.has("bank")) o.optString("bank") else null,
                direction = o.optTransactionDirection(),
                capturedAt = o.optLong("capturedAt"),
                templateHash = if (o.has("templateHash")) o.optString("templateHash") else null
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}
