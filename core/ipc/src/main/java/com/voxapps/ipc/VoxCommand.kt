package com.voxapps.ipc

import org.json.JSONObject

/**
 * A command authored by Commander and executed naively by a satellite (no NLU/LLM in the satellite —
 * just a `when(op)`). Optional fields are omitted from JSON when null.
 */
data class VoxCommand(
    val op: String,
    val text: String? = null,
    val title: String? = null,
    val category: String? = null,
    val limit: Int? = null,
    val domain: String? = null
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("op", op)
        text?.let { o.put("text", it) }
        title?.let { o.put("title", it) }
        category?.let { o.put("category", it) }
        limit?.let { o.put("limit", it) }
        domain?.let { o.put("domain", it) }
        return o.toString()
    }

    companion object {
        /** Lenient parse; returns null if the payload is blank or not valid JSON with an `op`. */
        fun fromJson(json: String?): VoxCommand? {
            if (json.isNullOrBlank()) return null
            return try {
                val o = JSONObject(json)
                val op = o.optString("op").takeIf { it.isNotBlank() } ?: return null
                VoxCommand(
                    op = op,
                    text = o.optStringOrNull("text"),
                    title = o.optStringOrNull("title"),
                    category = o.optStringOrNull("category"),
                    limit = if (o.has("limit")) o.optInt("limit") else null,
                    domain = o.optStringOrNull("domain")
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null
