package com.voxapps.datahygiene

import org.json.JSONObject

/**
 * The one canonical JSON-extraction helper every satellite's LLM-reply parser should use for a
 * nullable string field, instead of a bare `JSONObject.optString(key)` (which silently stringifies
 * a genuine JSON `null` into the literal text "null" via `JSONObject.NULL.toString()` — the exact
 * bug this module exists to prevent). The JSONObject-specific `isNull`/`has` presence guard stays
 * here; the actual "is this value garbage" predicate is [FieldCleaner.clean], shared with every
 * other cleanup path (manual UI, [RecordSanitizer]).
 */
fun JSONObject.optCleanString(key: String, fieldName: String? = key, recordLabel: String? = null): String? {
    if (isNull(key) || !has(key)) return null
    return FieldCleaner.clean(optString(key), fieldName, recordLabel)
}
