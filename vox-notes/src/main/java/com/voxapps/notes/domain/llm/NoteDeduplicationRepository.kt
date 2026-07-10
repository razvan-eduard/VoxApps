package com.voxapps.notes.domain.llm

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.voxapps.notes.data.preferences.DataStoreProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/**
 * Holds the *pending* note-deduplication suggestion awaiting user review (see the plan's confirmation
 * requirement — unlike category merge, this never applies automatically). Stores only the group ids,
 * not note content: content is resolved live against [com.voxapps.notes.data.NotesRepository.notes] at
 * render/apply time, so a group naturally invalidates itself (or partially shrinks) if a note was
 * edited or deleted since the suggestion arrived.
 */
class NoteDeduplicationRepository(context: Context) {

    private val dataStore = DataStoreProvider.get(context)

    private object Keys {
        val PENDING_GROUPS = stringPreferencesKey("pending_note_duplicate_groups")
    }

    val pendingGroupsFlow: Flow<List<DuplicateGroup>> = dataStore.data.map { prefs ->
        prefs[Keys.PENDING_GROUPS]?.let { decode(it) } ?: emptyList()
    }

    suspend fun setPendingGroups(groups: List<DuplicateGroup>) {
        dataStore.edit { it[Keys.PENDING_GROUPS] = encode(groups) }
    }

    suspend fun clearPendingGroups() {
        dataStore.edit { it.remove(Keys.PENDING_GROUPS) }
    }

    private fun encode(groups: List<DuplicateGroup>): String {
        val array = JSONArray()
        for (group in groups) {
            val o = JSONObject()
            o.put("keep", group.keepId)
            o.put("duplicates", JSONArray(group.duplicateIds))
            array.put(o)
        }
        return JSONObject().put("groups", array).toString()
    }

    private fun decode(json: String): List<DuplicateGroup> = NoteDeduplicationResultParser.parse(json) ?: emptyList()
}
