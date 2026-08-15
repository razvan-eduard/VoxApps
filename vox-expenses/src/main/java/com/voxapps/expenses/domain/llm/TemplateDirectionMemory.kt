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

    private data class Entry(
        val direction: String,
        val confirmations: Int,
        val conflicted: Boolean,
        /** The template's skeleton TEXT, for display only — matching is always by hash. Absent on
         *  entries learned before it was recorded; backfilled by [noteSkeleton] the next time a
         *  message with this hash arrives (confirmations come from record saves, where the
         *  original message no longer exists — capture time is the only moment both the hash and
         *  its text coexist). */
        val skeleton: String? = null
    )

    /** One learned template as shown to the user: the skeleton text (null until backfilled),
     *  what it has learned, and how far along it is. */
    data class TemplateView(
        val hash: String,
        val skeleton: String?,
        val direction: String,
        val confirmations: Int,
        val conflicted: Boolean,
        val answersDirection: Boolean
    )

    /** The inherited direction for [templateHash], or null when the memory declines. */
    suspend fun lookup(templateHash: String?): TransactionDirection? {
        if (templateHash == null) return null
        val e = templates()[templateHash] ?: return null
        if (e.conflicted || e.confirmations < MIN_CONFIRMATIONS) return null
        return if (e.direction.equals("incoming", ignoreCase = true)) TransactionDirection.INCOMING
        else TransactionDirection.OUTGOING
    }

    /**
     * Whether [templateHash] is known to produce real transactions. Every confirmation already
     * answers this — approving or edit-saving a record IS a human saying the template's messages
     * are payments — so the same counter serves, and a direction quarantine does not block it:
     * a template whose direction is disputed is still certainly a transaction template. The
     * asymmetry is deliberate and permanent: nothing ever teaches isPayment=false — a dismissal
     * can mean "duplicate" or "don't track this", so it fails the transcription bar.
     */
    suspend fun lookupIsPayment(templateHash: String?): Boolean {
        if (templateHash == null) return false
        val e = templates()[templateHash] ?: return false
        return e.confirmations >= MIN_CONFIRMATIONS
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
                // A quarantined template still counts confirmations: the human keeps saying
                // "this is a payment" even while the direction stays disputed.
                existing.conflicted -> existing.copy(confirmations = existing.confirmations + 1)
                // A re-taught entry (see reteach) has no direction yet — this confirmation is its
                // first, not a disagreement with the blank.
                existing.direction.isBlank() -> existing.copy(direction = direction.toJsonValue(), confirmations = existing.confirmations + 1)
                existing.direction != direction.toJsonValue() -> existing.copy(conflicted = true, confirmations = existing.confirmations + 1)
                else -> existing.copy(confirmations = existing.confirmations + 1)
            }
            prefs[Keys.TEMPLATES] = encodeTemplates(map)
        }
    }

    /** Records the skeleton TEXT against an already-learned [templateHash] — display backfill
     *  only, never creating entries (an entry for every observed message would accumulate one row
     *  per spam template; learning alone decides what exists here). Called at capture time, the
     *  only moment the hash and its text coexist. */
    suspend fun noteSkeleton(templateHash: String?, skeleton: String) {
        if (templateHash == null || skeleton.isBlank()) return
        dataStore.edit { prefs ->
            val map = decodeTemplates(prefs[Keys.TEMPLATES]).toMutableMap()
            val existing = map[templateHash] ?: return@edit
            if (existing.skeleton == null) {
                map[templateHash] = existing.copy(skeleton = skeleton)
                prefs[Keys.TEMPLATES] = encodeTemplates(map)
            }
        }
    }

    /** Everything learned, for the settings screen — most-confirmed first. */
    suspend fun snapshot(): List<TemplateView> =
        templates().map { (hash, e) ->
            TemplateView(
                hash = hash,
                skeleton = e.skeleton,
                direction = e.direction,
                confirmations = e.confirmations,
                conflicted = e.conflicted,
                answersDirection = !e.conflicted && e.confirmations >= MIN_CONFIRMATIONS
            )
        }.sortedByDescending { it.confirmations }

    /** Forgets [templateHash] entirely — its next message is a stranger again. */
    suspend fun forget(templateHash: String) {
        dataStore.edit { prefs ->
            val map = decodeTemplates(prefs[Keys.TEMPLATES]).toMutableMap()
            if (map.remove(templateHash) != null) {
                prefs[Keys.TEMPLATES] = encodeTemplates(map)
            }
        }
    }

    /** Clears a quarantine and restarts learning from zero — the explicit human override for a
     *  template the user knows is actually consistent. The skeleton text is kept (display state,
     *  not learned state); the confirmation count is not, because the old confirmations are
     *  exactly what the quarantine proved untrustworthy. */
    suspend fun reteach(templateHash: String) {
        dataStore.edit { prefs ->
            val map = decodeTemplates(prefs[Keys.TEMPLATES]).toMutableMap()
            val existing = map[templateHash] ?: return@edit
            map[templateHash] = Entry("", 0, false, existing.skeleton)
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

    /** The template behind [recordId], if the record came from a notification. The link is removed
     *  as it is read: one record is one human judgement, however many times it is re-saved, so a
     *  single record can never supply both confirmations a template needs. */
    suspend fun consumeLink(recordId: Long): String? {
        var hash: String? = null
        dataStore.edit { prefs ->
            val links = decodeLinks(prefs[Keys.RECORD_LINKS]).toMutableMap()
            hash = links.remove(recordId)?.hash
            prefs[Keys.RECORD_LINKS] = encodeLinks(pruneLinks(links))
        }
        return hash
    }

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
            val o = JSONObject().put("hash", hash).put("direction", e.direction)
                .put("confirmations", e.confirmations).put("conflicted", e.conflicted)
            e.skeleton?.let { o.put("skeleton", it) }
            arr.put(o)
        }
        return arr.toString()
    }

    private fun decodeTemplates(json: String?): Map<String, Entry> = try {
        if (json.isNullOrBlank()) emptyMap() else {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val hash = o.optString("hash").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                hash to Entry(
                    o.optString("direction"),
                    o.optInt("confirmations"),
                    o.optBoolean("conflicted"),
                    if (o.has("skeleton")) o.optString("skeleton") else null
                )
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
