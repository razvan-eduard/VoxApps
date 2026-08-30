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
    /** [VoxIpc.OP_SYNC_EXPORT]: container *names* (not ids — ids aren't stable across devices, see
     *  [VoxIpc.OP_SYNC_EXPORT]'s doc comment) to restrict the export to: bank accounts for Expenses,
     *  categories for Notes, calendars for Calendar. Null means everything is in scope; an EMPTY
     *  list means nothing is — the two are distinct on purpose, so an opt-in app with no containers
     *  shared yet exports nothing rather than everything. */
    val scopeNames: List<String>? = null,
    /** [VoxIpc.OP_SYNC_EXPORT]: record uids to include regardless of watermark, sync level, or
     *  scope — the manual "sync with device" push queue. [VoxIpc.OP_ENQUEUE_PUSH]: the uids being
     *  queued. */
    val uids: List<String>? = null,
    /** [VoxIpc.OP_SYNC_EXPORT]: continuation cursor from the previous page's reply (see
     *  `SyncDeltaKeys.NEXT_CURSOR`) — opaque to everyone but the satellite that minted it. Null
     *  asks for the first page. Page size rides in [limit]; with no [limit] the whole delta comes
     *  back in one page, the pre-pagination behavior. */
    val cursor: String? = null,
    /** [VoxIpc.OP_SYNC_MERGE]: identity of the device this delta came from, stamped onto rows the
     *  merge INSERTS so the receiving app can show and filter record provenance. Updates never
     *  touch an existing row's stamp. */
    val sourceDeviceId: String? = null,
    val sourceDeviceName: String? = null,
    /** [VoxIpc.OP_ENQUEUE_PUSH]: which paired peer the queued uids are destined for. */
    val peerId: String? = null,
    /** [VoxIpc.OP_ENQUEUE_PUSH]: package name of the satellite whose records the [uids] identify —
     *  the app Hub will address the forced export to. */
    val sourcePackage: String? = null,
    /** [VoxIpc.OP_MEDIA_CONTROL]: one of "status"/"play"/"pause"/"next"/"prev". */
    val mediaAction: String? = null,
    /** [VoxIpc.OP_IMPORT]: one of [VoxIpc.IMPORT_MODE_FULL_OVERRIDE]/[VoxIpc.IMPORT_MODE_MERGE]/
     *  [VoxIpc.IMPORT_MODE_ADDITIVE]. Null defaults to "merge" on the receiving end — the
     *  long-standing behavior before this field existed, so an older Hub build omitting it changes
     *  nothing for a satellite that already understands the field. */
    val importMode: String? = null
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
        uids?.let { o.put("uids", JSONArray(it)) }
        cursor?.let { o.put("cursor", it) }
        sourceDeviceId?.let { o.put("sourceDeviceId", it) }
        sourceDeviceName?.let { o.put("sourceDeviceName", it) }
        peerId?.let { o.put("peerId", it) }
        sourcePackage?.let { o.put("sourcePackage", it) }
        mediaAction?.let { o.put("mediaAction", it) }
        importMode?.let { o.put("importMode", it) }
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
                    uids = o.optJSONArray("uids")?.let { arr ->
                        (0 until arr.length()).map { arr.optString(it) }
                    },
                    cursor = o.optStringOrNull("cursor"),
                    sourceDeviceId = o.optStringOrNull("sourceDeviceId"),
                    sourceDeviceName = o.optStringOrNull("sourceDeviceName"),
                    peerId = o.optStringOrNull("peerId"),
                    sourcePackage = o.optStringOrNull("sourcePackage"),
                    mediaAction = o.optStringOrNull("mediaAction"),
                    importMode = o.optStringOrNull("importMode")
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null
