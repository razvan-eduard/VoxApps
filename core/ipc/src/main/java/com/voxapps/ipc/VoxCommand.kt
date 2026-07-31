package com.voxapps.ipc

import org.json.JSONArray
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
    val domain: String? = null,
    /** [VoxIpc.OP_EXPORT] scope: one of [VoxIpc.EXPORT_SCOPE_SETTINGS]/[VoxIpc.EXPORT_SCOPE_DATA]/
     *  [VoxIpc.EXPORT_SCOPE_BOTH]. Null defaults to "both" on the receiving end. */
    val exportScope: String? = null,
    /** [VoxIpc.OP_EXPORT]: whether to include real secrets (API keys) that are stripped by
     *  default — an explicit opt-in from Hub's export screen, off unless the user ticks it. */
    val includeSecrets: Boolean = false,
    /** [VoxIpc.OP_EXPORT]: whether to bundle receipt-photo files (Expenses only, today) alongside
     *  the JSON payload, returned via [VoxResult.attachmentUri] — an explicit opt-in from Hub's
     *  export screen, off unless the user ticks it. Satellites without photos simply ignore this. */
    val includePhotos: Boolean = false,
    /** Optional day-window bounds (epoch millis, inclusive) for [VoxIpc.OP_READ] — when both are
     *  present, a satellite that supports day-scoped reads (see [VoxDataTransferClient.requestDayRead])
     *  returns only records in this window instead of its full snapshot. Additive: satellites that
     *  don't check these fields are unaffected, and every existing caller that never sets them keeps
     *  getting the original full-snapshot behavior. */
    val dateFrom: Long? = null,
    val dateTo: Long? = null,
    /** [VoxIpc.OP_SYNC_EXPORT]: only return entries with `updatedAt > since` (and tombstones with
     *  `deletedAt > since`) — null/0 means "everything", used for a first-ever sync with a given
     *  peer. See [VoxDataTransferClient.requestSyncExport]. */
    val since: Long? = null,
    /** [VoxIpc.OP_SYNC_EXPORT]: category/layer *names* (not ids — ids aren't stable across devices,
     *  see [VoxIpc.OP_SYNC_EXPORT]'s doc comment) to restrict the export to. Null/empty means every
     *  category/layer is in scope. */
    val scopeNames: List<String>? = null,
    /** [VoxIpc.OP_MEDIA_CONTROL]: one of "status"/"play"/"pause"/"next"/"prev". */
    val mediaAction: String? = null
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("op", op)
        text?.let { o.put("text", it) }
        title?.let { o.put("title", it) }
        category?.let { o.put("category", it) }
        limit?.let { o.put("limit", it) }
        domain?.let { o.put("domain", it) }
        exportScope?.let { o.put("exportScope", it) }
        if (includeSecrets) o.put("includeSecrets", true)
        if (includePhotos) o.put("includePhotos", true)
        dateFrom?.let { o.put("dateFrom", it) }
        dateTo?.let { o.put("dateTo", it) }
        since?.let { o.put("since", it) }
        scopeNames?.let { o.put("scopeNames", JSONArray(it)) }
        mediaAction?.let { o.put("mediaAction", it) }
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
                    domain = o.optStringOrNull("domain"),
                    exportScope = o.optStringOrNull("exportScope"),
                    includeSecrets = o.optBoolean("includeSecrets", false),
                    includePhotos = o.optBoolean("includePhotos", false),
                    dateFrom = if (o.has("dateFrom")) o.optLong("dateFrom") else null,
                    dateTo = if (o.has("dateTo")) o.optLong("dateTo") else null,
                    since = if (o.has("since")) o.optLong("since") else null,
                    scopeNames = o.optJSONArray("scopeNames")?.let { arr ->
                        (0 until arr.length()).map { arr.optString(it) }
                    },
                    mediaAction = o.optStringOrNull("mediaAction")
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null
