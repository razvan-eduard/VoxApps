package com.voxapps.ipc

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builder for [VoxIpc.OP_GET_FIELD_SCHEMA] responses — shared so the satellites (calendar/expenses/
 * notes) don't each hand-roll the same field-descriptor shape. Field `type` values: "text", "number",
 * "datetime", "bool", "enum" (fixed [options]), "category" (live [options] from the satellite's own
 * DB), "list" (nested repeatable group via [itemFields]), "readonly". Universal record fields (uid,
 * createdAt, updatedAt) are deliberately not part of this schema — they round-trip unconditionally on
 * every record, same as [VoxIpc.OP_SYNC_MERGE] already assumes.
 */
object VoxFormSchema {
    fun field(
        key: String,
        label: String,
        type: String,
        required: Boolean = false,
        options: List<String>? = null,
        itemFields: List<JSONObject>? = null,
    ): JSONObject = JSONObject().apply {
        put("key", key)
        put("label", label)
        put("type", type)
        put("required", required)
        options?.let { put("options", JSONArray(it)) }
        itemFields?.let { put("itemFields", JSONArray(it)) }
    }

    fun domainSchema(
        domain: String,
        titleField: String,
        fields: List<JSONObject>,
        titleFallbackField: String? = null,
        subtitleFields: List<String> = emptyList(),
        sortField: String? = null,
        sortDescending: Boolean = true,
        upcomingOnlyField: String? = null,
    ): JSONObject = JSONObject().apply {
        put("domain", domain)
        put("titleField", titleField)
        titleFallbackField?.let { put("titleFallbackField", it) }
        put("subtitleFields", JSONArray(subtitleFields))
        sortField?.let { put("sortField", it) }
        put("sortDescending", sortDescending)
        upcomingOnlyField?.let { put("upcomingOnlyField", it) }
        put("fields", JSONArray(fields))
    }
}
