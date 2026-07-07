package com.voxapps.ipc

import org.json.JSONObject

/**
 * The satellite's reply to a read command, returned as the ordered-broadcast `resultData`. [text]
 * is either the notes payload (when [ok]) or a user-facing message Commander can speak (when locked).
 */
data class VoxResult(
    val ok: Boolean,
    val text: String
) {
    fun toJson(): String = JSONObject().put("ok", ok).put("text", text).toString()

    companion object {
        fun fromJson(json: String?): VoxResult? {
            if (json.isNullOrBlank()) return null
            return try {
                val o = JSONObject(json)
                VoxResult(ok = o.optBoolean("ok", false), text = o.optString("text"))
            } catch (e: Exception) {
                null
            }
        }
    }
}
