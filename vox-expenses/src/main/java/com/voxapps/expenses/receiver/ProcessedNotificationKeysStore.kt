package com.voxapps.expenses.receiver

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.voxapps.expenses.data.preferences.DataStoreProvider
import kotlinx.coroutines.flow.first
import org.json.JSONArray

/**
 * Tracks which notification keys [PaymentNotificationListenerService] has already dispatched for LLM
 * triage, so its `onListenerConnected()` catch-up (`getActiveNotifications()`, for notifications
 * posted while the service process was killed by OEM background-app hibernation — confirmed
 * happening on-device via "AppFastHibernation" system logs) doesn't re-dispatch a still-visible
 * notification it already processed on a prior connection.
 *
 * Stored as an ordered JSON array (oldest first) rather than a DataStore string-set, so it can be
 * FIFO-capped at [MAX_KEYS] — a plain string-set has no defined iteration order to evict by.
 */
class ProcessedNotificationKeysStore(context: Context) {

    private val dataStore = DataStoreProvider.get(context)

    private object Keys {
        val PROCESSED_KEYS = stringPreferencesKey("processed_notification_keys")
    }

    suspend fun isProcessed(key: String): Boolean = decode(dataStore.data.first()[Keys.PROCESSED_KEYS]).contains(key)

    suspend fun markProcessed(key: String) {
        dataStore.edit {
            val updated = (decode(it[Keys.PROCESSED_KEYS]) + key).takeLast(MAX_KEYS)
            it[Keys.PROCESSED_KEYS] = JSONArray(updated).toString()
        }
    }

    private fun decode(json: String?): List<String> {
        if (json == null) return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { array.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    companion object {
        private const val MAX_KEYS = 100
    }
}
