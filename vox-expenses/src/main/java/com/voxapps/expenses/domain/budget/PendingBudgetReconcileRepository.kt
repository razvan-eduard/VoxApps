package com.voxapps.expenses.domain.budget

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.voxapps.expenses.data.preferences.DataStoreProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/** A balance the notification pipeline proposed while
 *  [com.voxapps.expenses.data.preferences.ExpensesSettings.notificationBalanceReconcileMode] is
 *  SUGGEST rather than AUTO — see [NotificationBalanceReconciler]. */
data class PendingBudgetReconcile(
    val accountId: Long,
    val currencyCode: String,
    val remaining: Double,
    val statedAt: Long
)

/**
 * Holds *pending* balance suggestions, awaiting a person's Apply/Dismiss on the matching
 * [com.voxapps.expenses.data.AccountBudget] row — mirrors the pending-review pattern of
 * [com.voxapps.expenses.domain.llm.PendingNotificationExpenseRepository], but keyed on
 * (accountId, currencyCode) rather than a synthetic id, the same key an [com.voxapps.expenses.data.AccountBudget]
 * row is itself DB-unique on: there is naturally at most one live suggestion per budget at a time.
 */
class PendingBudgetReconcileRepository(context: Context) {

    private val dataStore = DataStoreProvider.get(context)

    private object Keys {
        val PENDING = stringPreferencesKey("pending_budget_reconciles")
    }

    val pendingFlow: Flow<List<PendingBudgetReconcile>> = dataStore.data.map { prefs ->
        prefs[Keys.PENDING]?.let { decode(it) } ?: emptyList()
    }

    suspend fun addPending(entry: PendingBudgetReconcile) {
        dataStore.edit {
            val current = it[Keys.PENDING]?.let { json -> decode(json) } ?: emptyList()
            it[Keys.PENDING] = encode(mergeByAccountKey(current, entry))
        }
    }

    suspend fun removePending(accountId: Long, currencyCode: String) {
        dataStore.edit {
            val current = it[Keys.PENDING]?.let { json -> decode(json) } ?: emptyList()
            it[Keys.PENDING] = encode(
                current.filterNot { e -> e.accountId == accountId && e.currencyCode.equals(currencyCode, ignoreCase = true) }
            )
        }
    }

    suspend fun snapshot(): List<PendingBudgetReconcile> = pendingFlow.first()

    private fun encode(entries: List<PendingBudgetReconcile>): String {
        val array = JSONArray()
        for (e in entries) {
            val o = JSONObject()
            o.put("accountId", e.accountId)
            o.put("currencyCode", e.currencyCode)
            o.put("remaining", e.remaining)
            o.put("statedAt", e.statedAt)
            array.put(o)
        }
        return array.toString()
    }

    private fun decode(json: String): List<PendingBudgetReconcile> = try {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            PendingBudgetReconcile(
                accountId = o.optLong("accountId"),
                currencyCode = o.optString("currencyCode"),
                remaining = o.optDouble("remaining"),
                statedAt = o.optLong("statedAt")
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}

/**
 * Folds [incoming] into [current] by (accountId, currencyCode) rather than appending. Unlike
 * [com.voxapps.expenses.domain.llm.mergeBySourceKey], two entries sharing this key are not
 * necessarily readings of the same notification — a second purchase can state the same account's
 * balance an hour after the first — so the newer STATEMENT wins by [PendingBudgetReconcile.statedAt],
 * not by which was processed later: a forced re-check of an old notification must not be able to
 * clobber a genuinely newer suggestion just because it happened to be re-read more recently.
 */
internal fun mergeByAccountKey(
    current: List<PendingBudgetReconcile>,
    incoming: PendingBudgetReconcile
): List<PendingBudgetReconcile> {
    val index = current.indexOfFirst {
        it.accountId == incoming.accountId && it.currencyCode.equals(incoming.currencyCode, ignoreCase = true)
    }
    if (index < 0) return current + incoming
    if (incoming.statedAt < current[index].statedAt) return current
    return current.toMutableList().also { it[index] = incoming }
}
