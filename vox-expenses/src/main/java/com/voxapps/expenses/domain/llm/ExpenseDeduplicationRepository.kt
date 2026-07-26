package com.voxapps.expenses.domain.llm

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.voxapps.expenses.data.preferences.DataStoreProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/**
 * Holds the *pending* expense-deduplication suggestion awaiting user review (mirrors vox-notes'
 * NoteDeduplicationRepository) — never applies automatically. Stores only the group ids, not expense
 * content: content is resolved live against [com.voxapps.expenses.data.ExpensesRepository.expenses] at
 * render/apply time, so a group naturally invalidates itself (or partially shrinks) if an expense was
 * edited or deleted since the suggestion arrived.
 */
class ExpenseDeduplicationRepository(context: Context) {

    private val dataStore = DataStoreProvider.get(context)

    private object Keys {
        val PENDING_GROUPS = stringPreferencesKey("pending_expense_duplicate_groups")
    }

    val pendingGroupsFlow: Flow<List<DuplicateGroup>> = dataStore.data.map { prefs ->
        prefs[Keys.PENDING_GROUPS]?.let { decode(it) } ?: emptyList()
    }

    suspend fun setPendingGroups(groups: List<DuplicateGroup>) {
        dataStore.edit { it[Keys.PENDING_GROUPS] = encode(groups) }
    }

    /** Adds [newGroups] to whatever's already pending instead of replacing it — a manual/scheduled
     *  check shouldn't erase suggestions still awaiting review from a previous run or an
     *  independently-arriving AI reply. Groups sharing the same [DuplicateGroup.keepId] are unioned
     *  rather than appended as separate entries — re-running the same check (e.g. repeated taps of
     *  "Check for duplicates now") re-detects the same anchor expense every time, and appending a
     *  fresh, structurally-identical group on every tap grew the pending list without bound. */
    suspend fun mergePendingGroups(newGroups: List<DuplicateGroup>) {
        if (newGroups.isEmpty()) return
        dataStore.edit { prefs ->
            val current = prefs[Keys.PENDING_GROUPS]?.let { decode(it) } ?: emptyList()
            prefs[Keys.PENDING_GROUPS] = encode(mergeGroupsByKeepId(current, newGroups))
        }
    }

    /** Removes exactly [groups] from the pending list, leaving every other suggestion untouched —
     *  used both by approving a subset (only the just-applied groups should disappear) and by
     *  dismissing a single bad suggestion, as opposed to [clearPendingGroups]'s "drop everything." */
    suspend fun removeGroups(groups: List<DuplicateGroup>) {
        if (groups.isEmpty()) return
        dataStore.edit { prefs ->
            val current = prefs[Keys.PENDING_GROUPS]?.let { decode(it) } ?: emptyList()
            prefs[Keys.PENDING_GROUPS] = encode(current.filterNot { it in groups })
        }
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

    private fun decode(json: String): List<DuplicateGroup> = ExpenseDeduplicationResultParser.parse(json) ?: emptyList()
}

/** Merges [newGroups] into [current], keyed by [DuplicateGroup.keepId] — groups anchored to the same
 *  kept expense are unioned (their duplicateIds combined) rather than kept as separate list entries.
 *  A pure top-level function (not a method on [ExpenseDeduplicationRepository]) so it's unit-testable
 *  without a DataStore-backed Context. */
internal fun mergeGroupsByKeepId(current: List<DuplicateGroup>, newGroups: List<DuplicateGroup>): List<DuplicateGroup> {
    val byKeepId = LinkedHashMap<Long, DuplicateGroup>()
    for (group in current) byKeepId[group.keepId] = group
    for (group in newGroups) {
        val existing = byKeepId[group.keepId]
        byKeepId[group.keepId] = if (existing == null) {
            group
        } else {
            DuplicateGroup(group.keepId, (existing.duplicateIds + group.duplicateIds).distinct())
        }
    }
    return byKeepId.values.toList()
}
