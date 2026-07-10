package com.voxapps.notes.domain.llm

import org.json.JSONObject

/** One proposed duplicate resolution: keep [keepId], remove every id in [duplicateIds]. */
data class DuplicateGroup(val keepId: Long, val duplicateIds: List<Long>)

/**
 * Parses the note-deduplication LLM response `{"groups": [{"keep": <id>, "duplicates": [<id>,...]}]}`
 * into a list of [DuplicateGroup]. Tolerant of individual malformed groups (skipped rather than
 * failing the whole parse) — only returns null if the top-level JSON itself can't be read at all.
 */
object NoteDeduplicationResultParser {
    fun parse(json: String): List<DuplicateGroup>? = try {
        val o = JSONObject(json)
        val groupsArray = o.optJSONArray("groups")
        if (groupsArray == null) {
            emptyList()
        } else {
            (0 until groupsArray.length()).mapNotNull { i ->
                val groupObj = groupsArray.optJSONObject(i) ?: return@mapNotNull null
                if (!groupObj.has("keep")) return@mapNotNull null
                val keepId = groupObj.optLong("keep", -1L)
                if (keepId < 0) return@mapNotNull null
                val duplicatesArray = groupObj.optJSONArray("duplicates") ?: return@mapNotNull null
                val duplicateIds = (0 until duplicatesArray.length())
                    .map { duplicatesArray.optLong(it, -1L) }
                    .filter { it >= 0 }
                if (duplicateIds.isEmpty()) null else DuplicateGroup(keepId, duplicateIds)
            }
        }
    } catch (e: Exception) {
        null
    }
}
