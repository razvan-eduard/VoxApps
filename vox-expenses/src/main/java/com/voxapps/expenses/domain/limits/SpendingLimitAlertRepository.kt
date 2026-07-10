package com.voxapps.expenses.domain.limits

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.voxapps.expenses.data.preferences.DataStoreProvider
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/**
 * Tracks, per spending limit id, the last period key ([SpendingPeriod.periodKey]) an "exceeded" alert
 * was already posted for — so [SpendingLimitCheckWorker] notifies at most once per window instead of
 * on every periodic run while a limit stays exceeded. Naturally resets when the period rolls over,
 * since the key itself changes.
 */
class SpendingLimitAlertRepository(context: Context) {

    private val dataStore = DataStoreProvider.get(context)

    private object Keys {
        val LAST_ALERTED = stringPreferencesKey("spending_limit_last_alerted_period")
    }

    suspend fun wasAlreadyAlerted(limitId: Long, periodKey: String): Boolean {
        val map = decode(dataStore.data.first()[Keys.LAST_ALERTED])
        return map[limitId.toString()] == periodKey
    }

    suspend fun markAlerted(limitId: Long, periodKey: String) {
        dataStore.edit { prefs ->
            val map = decode(prefs[Keys.LAST_ALERTED]).toMutableMap()
            map[limitId.toString()] = periodKey
            prefs[Keys.LAST_ALERTED] = encode(map)
        }
    }

    /** Drops bookkeeping for a limit that no longer exists. */
    suspend fun forget(limitId: Long) {
        dataStore.edit { prefs ->
            val map = decode(prefs[Keys.LAST_ALERTED]).toMutableMap()
            map.remove(limitId.toString())
            prefs[Keys.LAST_ALERTED] = encode(map)
        }
    }

    private fun encode(map: Map<String, String>): String {
        val o = JSONObject()
        for ((k, v) in map) o.put(k, v)
        return o.toString()
    }

    private fun decode(json: String?): Map<String, String> = try {
        if (json == null) emptyMap() else {
            val o = JSONObject(json)
            o.keys().asSequence().associateWith { o.optString(it) }
        }
    } catch (e: Exception) {
        emptyMap()
    }
}
