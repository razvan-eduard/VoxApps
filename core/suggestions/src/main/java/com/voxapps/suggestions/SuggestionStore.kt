package com.voxapps.suggestions

import com.voxapps.logging.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val TAG = "SuggestionStore"

/** A proposal worth showing: the field, what it says now, and what is being offered instead. */
data class OfferedSuggestion(
    val field: SuggestableField,
    val current: String?,
    val proposed: String?,
    val sourceTag: String?
)

/**
 * The lifecycle of a suggestion, once, for every satellite: what is worth showing, what happens when
 * one is accepted, and when the last of them is gone.
 *
 * The rules are small but they were the part that got written differently each time. A proposal
 * equal to what the record already says is not shown — it is not a suggestion, it is agreement. A
 * field the satellite did not declare is dropped on arrival rather than stored and never rendered.
 * And accepting is only allowed to remove the proposal if the satellite says it actually applied it.
 */
class SuggestionStore(
    private val dao: FieldSuggestionDao,
    private val target: SuggestionTarget
) {

    private val declared: Map<String, SuggestableField>
        get() = target.suggestableFields.associateBy { it.key }

    /**
     * Record what a reply proposed. Fields this satellite never declared are discarded here, at the
     * edge, so nothing unrenderable is ever stored.
     */
    suspend fun offer(recordId: Long, values: Map<String, String?>, sourceTag: String? = null) {
        val known = declared
        val (kept, dropped) = values.entries.partition { it.key in known }
        if (dropped.isNotEmpty()) {
            Logger.d(TAG, "Ignoring ${dropped.size} undeclared field(s): ${dropped.joinToString { it.key }}")
        }
        if (kept.isEmpty()) return
        dao.upsert(kept.map { FieldSuggestion(recordId, it.key, it.value, sourceTag) })
    }

    /** Everything worth showing for this record, live — declared, and different from what is there. */
    fun offered(recordId: Long): Flow<List<OfferedSuggestion>> =
        dao.forRecord(recordId).map { stored ->
            val known = declared
            stored.mapNotNull { row ->
                val field = known[row.fieldKey] ?: return@mapNotNull null
                val current = target.currentValue(recordId, row.fieldKey)
                if (current == row.value) null
                else OfferedSuggestion(field, current, row.value, row.sourceTag)
            }.sortedBy { offered -> target.suggestableFields.indexOfFirst { it.key == offered.field.key } }
        }

    /**
     * Apply one. The proposal is removed only if the satellite reports it was written — an accept
     * that could not be carried out leaves the offer standing rather than losing it silently.
     */
    suspend fun accept(recordId: Long, fieldKey: String, value: String?): Boolean {
        val tag = sourceOf(recordId, fieldKey)
        val applied = target.applyValue(recordId, fieldKey, value)
        if (!applied) {
            Logger.w(TAG, "$fieldKey was not applied — the suggestion stays")
            return false
        }
        dao.clearField(recordId, fieldKey)
        disposeIfSpent(recordId, tag)
        return true
    }

    /** Refuse one, without touching the record. */
    suspend fun dismiss(recordId: Long, fieldKey: String) {
        val tag = sourceOf(recordId, fieldKey)
        dao.clearField(recordId, fieldKey)
        disposeIfSpent(recordId, tag)
    }

    /** Everything for this record, gone — what saving it means. */
    suspend fun clear(recordId: Long) = dao.clearRecord(recordId)

    private suspend fun sourceOf(recordId: Long, fieldKey: String): String? =
        dao.snapshot(recordId).firstOrNull { it.fieldKey == fieldKey }?.sourceTag

    /**
     * When the last proposal carrying a source is gone, the source has nothing left to offer and the
     * satellite may dispose of it. Read before the removal and checked after, because it is the
     * removal itself that can empty it — and either accepting or dismissing can be the one that does.
     */
    private suspend fun disposeIfSpent(recordId: Long, tag: String?) {
        if (tag == null) return
        if (dao.snapshot(recordId).none { it.sourceTag == tag }) {
            target.discardSource(recordId, tag)
        }
    }
}
