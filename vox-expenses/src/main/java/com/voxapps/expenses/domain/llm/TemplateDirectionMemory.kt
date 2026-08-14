package com.voxapps.expenses.domain.llm

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.voxapps.expenses.data.TransactionDirection
import com.voxapps.expenses.data.preferences.DataStoreProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/**
 * What a human has said notification templates mean — the direction axis of the same idea the
 * merchant→category memory already applies to vendors.
 *
 * A bank emits notifications from fixed templates; [com.voxapps.textmatch.extract.TemplateSkeleton]
 * reduces a message to its template's byte-shape. When the user confirms a record's direction —
 * approving it from review, or saving it from the editor — that judgement is recorded against the
 * template, and the next message shaped exactly the same inherits it as a fact: the bank said the
 * same sentence again, and a person already said what that sentence is. No language is parsed and
 * nothing is inferred.
 *
 * The rules are certainty-or-decline throughout:
 *  - a template answers only after [MIN_CONFIRMATIONS] unanimous human confirmations;
 *  - the first conflicting confirmation quarantines the template permanently — a template whose
 *    meaning apparently varies is exactly one whose meaning lives outside what the skeleton
 *    normalizes, and it goes back to the model for good;
 *  - anything unknown answers null.
 *
 * Confirmations come only from human actions, never from the model's own unreviewed output —
 * seeding a cache from the thing whose noise it exists to remove would launder the noise.
 */
class TemplateDirectionMemory(context: Context) {

    private val dataStore = DataStoreProvider.get(context)

    private object Keys {
        val TEMPLATES = stringPreferencesKey("template_direction_memory")
        val RECORD_LINKS = stringPreferencesKey("template_record_links")
    }

    companion object {
        /** Two unanimous sightings: inheritance then rests on "the bank changed what a sentence
         *  means between two occurrences", which templates exist to prevent. */
        private const val MIN_CONFIRMATIONS = 2

        /** Links older than this are orphans of records the user never touched. */
        private const val LINK_MAX_AGE_MS = 90L * 24 * 60 * 60 * 1000
    }

    private data class Entry(val direction: String, val confirmations: Int, val conflicted: Boolean)

    /** The inherited direction for [templateHash], or null when the memory declines. */
    suspend fun lookup(templateHash: String?): TransactionDirection? {
        if (templateHash == null) return null
        val e = templates()[templateHash] ?: return null
        if (e.conflicted || e.confirmations < MIN_CONFIRMATIONS) return null
        return if (e.direction.equals("incoming", ignoreCase = true)) TransactionDirection.INCOMING
        else TransactionDirection.OUTGOING
    }

    /** A human confirmed [direction] for [templateHash]. Unanimity grows the count; disagreement
     *  quarantines the template permanently. */
    suspend fun confirm(templateHash: String?, direction: TransactionDirection) {
        if (templateHash == null) return
        dataStore.edit { prefs ->
            val map = decodeTemplates(prefs[Keys.TEMPLATES]).toMutableMap()
            val existing = map[templateHash]
            map[templateHash] = when {
                existing == null -> Entry(direction.toJsonValue(), 1, false)
                existing.conflicted -> existing
                existing.direction != direction.toJsonValue() -> existing.copy(conflicted = true)
                else -> existing.copy(confirmations = existing.confirmations + 1)
            }
            prefs[Keys.TEMPLATES] = encodeTemplates(map)
        }
    }

    /** Remembers which template produced auto-created record [recordId], so a later edit-save of
     *  that record can act as the human confirmation. */
    suspend fun linkRecord(recordId: Long, templateHash: String) {
        dataStore.edit { prefs ->
            val links = decodeLinks(prefs[Keys.RECORD_LINKS]).toMutableMap()
            links[recordId] = Link(templateHash, System.currentTimeMillis())
            prefs[Keys.RECORD_LINKS] = encodeLinks(pruneLinks(links))
        }
    }

    /** The template behind [recordId], if the record came from a notification. */
    suspend fun linkedTemplate(recordId: Long): String? =
        decodeLinks(dataStore.data.map { it[Keys.RECORD_LINKS] }.first())[recordId]?.hash

    private data class Link(val hash: String, val at: Long)

    private fun pruneLinks(links: Map<Long, Link>): Map<Long, Link> {
        val cutoff = System.currentTimeMillis() - LINK_MAX_AGE_MS
        return links.filterValues { it.at >= cutoff }
    }

    private suspend fun templates(): Map<String, Entry> =
        decodeTemplates(dataStore.data.map { it[Keys.TEMPLATES] }.first())

    private fun encodeTemplates(map: Map<String, Entry>): String {
        val arr = JSONArray()
        for ((hash, e) in map) {
            arr.put(JSONObject().put("hash", hash).put("direction", e.direction)
                .put("confirmations", e.confirmations).put("conflicted", e.conflicted))
        }
        return arr.toString()
    }

    private fun decodeTemplates(json: String?): Map<String, Entry> = try {
        if (json.isNullOrBlank()) emptyMap() else {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val hash = o.optString("hash").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                hash to Entry(o.optString("direction"), o.optInt("confirmations"), o.optBoolean("conflicted"))
            }.toMap()
        }
    } catch (e: Exception) {
        emptyMap()
    }

    private fun encodeLinks(map: Map<Long, Link>): String {
        val arr = JSONArray()
        for ((id, l) in map) arr.put(JSONObject().put("id", id).put("hash", l.hash).put("at", l.at))
        return arr.toString()
    }

    private fun decodeLinks(json: String?): Map<Long, Link> = try {
        if (json.isNullOrBlank()) emptyMap() else {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optLong("id", -1L).takeIf { it >= 0 } ?: return@mapNotNull null
                id to Link(o.optString("hash"), o.optLong("at"))
            }.toMap()
        }
    } catch (e: Exception) {
        emptyMap()
    }
}
