package com.voxapps.backup

import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads a string field as `null` when it is absent or an explicit JSON `null`, instead of the
 * literal text `"null"` that a bare [JSONObject.optString] returns for the latter (it stringifies
 * `JSONObject.NULL`).
 *
 * Each of the three satellite export/import handlers carried a private copy of exactly this
 * function. Distinct from `:core:datahygiene`'s `optCleanString`, which additionally runs the value
 * through `FieldCleaner` to reject model-generated garbage — that belongs on LLM output, whereas a
 * backup payload is this app's own earlier export and needs presence handling, not sanitising.
 */
fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null

/**
 * Merges an imported reference table (calendars, to-do lists, categories) into the local one by
 * name, creating only the entries that don't already exist.
 *
 * Reference tables can't be replaced wholesale the way records can: records point at them by id,
 * so replacing one would orphan every untouched local record referencing an old id. Matching on
 * a case-insensitively trimmed name instead means a device that already has "Groceries" adopts
 * the imported rows rather than growing a second copy.
 *
 * @param nameOf the display name to match on, for both existing rows and imported objects.
 * @param create called only for a name with no local match; returns the new row id, or a
 *   non-positive value if it could not be created.
 * @return [MergeResult.idMap], mapping each imported id to its local counterpart (null when the
 *   row was skipped or creation failed), which callers use to rewrite foreign keys on records.
 */
suspend fun <T> mergeByName(
    imported: JSONArray,
    existing: List<T>,
    nameOf: (T) -> String,
    idOf: (T) -> Long,
    importedNameOf: (JSONObject) -> String,
    create: suspend (JSONObject, String) -> Long
): MergeResult {
    val nameToId = existing.associate { nameOf(it).lowercase() to idOf(it) }.toMutableMap()
    val idMap = mutableMapOf<Long, Long?>()
    var created = 0
    for (i in 0 until imported.length()) {
        val obj = imported.getJSONObject(i)
        val name = importedNameOf(obj).trim()
        if (name.isEmpty()) continue
        val localId = nameToId[name.lowercase()] ?: run {
            val newId = create(obj, name)
            if (newId > 0) {
                created++
                nameToId[name.lowercase()] = newId
            }
            newId.takeIf { it > 0 }
        }
        idMap[obj.optLong("id")] = localId
    }
    return MergeResult(idMap, created)
}

/** @property idMap imported id -> local id (null when unmatched and not created). */
data class MergeResult(val idMap: Map<Long, Long?>, val created: Int)
