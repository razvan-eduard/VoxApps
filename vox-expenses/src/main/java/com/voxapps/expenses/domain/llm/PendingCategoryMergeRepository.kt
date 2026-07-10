package com.voxapps.expenses.domain.llm

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.voxapps.expenses.data.preferences.DataStoreProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject

/**
 * Holds the *pending* category-merge suggestion awaiting user review — the deliberate deviation from
 * vox-notes, where the equivalent auto-applies. Merging expense categories can reshuffle real
 * financial data/reporting, so nothing is applied until the user explicitly confirms it here (mirrors
 * vox-notes' NoteDeduplicationRepository's pending-review pattern, just storing a flat name map
 * instead of id groups).
 */
class PendingCategoryMergeRepository(context: Context) {

    private val dataStore = DataStoreProvider.get(context)

    private object Keys {
        val PENDING_MAPPING = stringPreferencesKey("pending_category_merge_mapping")
    }

    val pendingMappingFlow: Flow<Map<String, String>> = dataStore.data.map { prefs ->
        prefs[Keys.PENDING_MAPPING]?.let { decode(it) } ?: emptyMap()
    }

    suspend fun setPendingMapping(mapping: Map<String, String>) {
        dataStore.edit { it[Keys.PENDING_MAPPING] = encode(mapping) }
    }

    suspend fun clearPendingMapping() {
        dataStore.edit { it.remove(Keys.PENDING_MAPPING) }
    }

    private fun encode(mapping: Map<String, String>): String {
        val o = JSONObject()
        for ((oldName, canonicalName) in mapping) o.put(oldName, canonicalName)
        return o.toString()
    }

    private fun decode(json: String): Map<String, String> = try {
        val o = JSONObject(json)
        o.keys().asSequence().associateWith { o.optString(it) }
    } catch (e: Exception) {
        emptyMap()
    }
}
