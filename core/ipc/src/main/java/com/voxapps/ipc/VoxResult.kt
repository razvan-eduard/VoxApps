package com.voxapps.ipc

import org.json.JSONObject

/**
 * The satellite's reply to a read command, returned as the ordered-broadcast `resultData`. [text]
 * is either the notes payload (when [ok]) or a user-facing message Commander can speak (when locked).
 * [attachmentUri] optionally carries a content:// URI to a file the caller has been granted read
 * access to (see [VoxIpc.HUB_PACKAGE]'s doc comment) — used by [VoxIpc.OP_EXPORT] to hand back a
 * zip of Expenses' receipt photos alongside the JSON text, without inlining binary bytes into this
 * string-only IPC channel.
 */
data class VoxResult(
    val ok: Boolean,
    val text: String,
    val attachmentUri: String? = null
) {
    fun toJson(): String = JSONObject().put("ok", ok).put("text", text).apply {
        attachmentUri?.let { put("attachmentUri", it) }
    }.toString()

    companion object {
        fun fromJson(json: String?): VoxResult? {
            if (json.isNullOrBlank()) return null
            return try {
                val o = JSONObject(json)
                VoxResult(
                    ok = o.optBoolean("ok", false),
                    text = o.optString("text"),
                    attachmentUri = if (o.has("attachmentUri") && !o.isNull("attachmentUri")) o.optString("attachmentUri") else null
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
