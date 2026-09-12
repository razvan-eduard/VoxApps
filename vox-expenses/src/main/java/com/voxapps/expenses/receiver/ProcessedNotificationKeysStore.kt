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
 * Keyed on the notification's key *and* a fingerprint of what was actually read from it, not the key
 * alone. A key survives an in-place update — confirmed on-device: Google Wallet posts a placeholder
 * ("Google Wallet" / "View your purchase", no merchant) and then updates the same notification with
 * the real one ("LIDL RO-490" / the amount and card) a moment later. Keying on the bare key made the
 * update invisible: the placeholder's read marked the key processed, so the richer update — same key,
 * different content — was silently dropped, and the record was stuck with whatever the placeholder
 * could read (usually nothing, falling back to the bank's name). A key paired with its content lets
 * a same-key repost through when the content actually changed, while still deduping a genuine repost
 * of the same message (a ranking change, a rebind) whose content is identical. Safe to let an update
 * through more than once: [PaymentNotificationListenerService]'s near-duplicate/second-notice folding
 * already exists for exactly this, and prefers the reading with the more complete data either way.
 *
 * Stored as an ordered JSON array (oldest first) rather than a DataStore string-set, so it can be
 * FIFO-capped at [MAX_KEYS] — a plain string-set has no defined iteration order to evict by.
 */
class ProcessedNotificationKeysStore(context: Context) {

    private val dataStore = DataStoreProvider.get(context)

    private object Keys {
        val PROCESSED_KEYS = stringPreferencesKey("processed_notification_keys")
    }

    suspend fun isProcessed(key: String, contentHash: Int): Boolean =
        decode(dataStore.data.first()[Keys.PROCESSED_KEYS]).contains(entryFor(key, contentHash))

    suspend fun markProcessed(key: String, contentHash: Int) {
        dataStore.edit {
            val updated = (decode(it[Keys.PROCESSED_KEYS]) + entryFor(key, contentHash)).takeLast(MAX_KEYS)
            it[Keys.PROCESSED_KEYS] = JSONArray(updated).toString()
        }
    }

    // "#" never appears in a notification key (Android composes it from the package name, an
    // optional user id and tag, and the notification id — see StatusBarNotification.getKey()), so a
    // plain join is unambiguous without needing to escape either side.
    private fun entryFor(key: String, contentHash: Int) = "$key#$contentHash"

    /** Wipes the whole processed-keys history — the "force-check notifications" settings button
     *  calls this before requesting a rebind, so a key that got permanently (mis)marked processed
     *  by an old build (e.g. before [PaymentNotificationListenerService] stopped marking at dispatch
     *  time instead of on a confirmed Commander reply) isn't stuck unrecoverable forever. Safe to
     *  clear entirely: at worst a handful of already-successfully-processed notifications get
     *  re-dispatched once, and ExpenseDuplicateChecker/the pending-review queue's own dedup already
     *  guard against that turning into a real duplicate expense. */
    suspend fun clearAll() {
        dataStore.edit { it[Keys.PROCESSED_KEYS] = JSONArray(emptyList<String>()).toString() }
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
