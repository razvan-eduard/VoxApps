package com.voxapps.ipc

import org.json.JSONArray
import org.json.JSONObject

/**
 * A generic, domain-agnostic request for Commander's currently-selected LLM to process [promptText]
 * and reply asynchronously. Commander never interprets [task] or [data] — they are entirely owned by
 * the calling satellite, which is what lets new features (e.g. a future "summarize note" task) ship
 * without any change to Commander or this contract. [data] is optional metadata for logging only
 * (Commander's actual instructions must already be folded into [promptText] by the caller).
 * [sourcePackage] is required because a plain broadcast doesn't reliably expose the caller's identity —
 * Commander uses it as the explicit-intent target for the async [VoxLlmResult] reply.
 */
data class VoxLlmRequest(
    val sourcePackage: String,
    val task: String,
    val promptText: String,
    val data: List<String> = emptyList()
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("sourcePackage", sourcePackage)
        o.put("task", task)
        o.put("promptText", promptText)
        o.put("data", JSONArray(data))
        return o.toString()
    }

    companion object {
        /** Lenient parse; returns null if the payload is blank or missing a required field. */
        fun fromJson(json: String?): VoxLlmRequest? {
            if (json.isNullOrBlank()) return null
            return try {
                val o = JSONObject(json)
                val sourcePackage = o.optStringOrNull("sourcePackage") ?: return null
                val task = o.optStringOrNull("task") ?: return null
                val promptText = o.optStringOrNull("promptText") ?: return null
                val dataArray = o.optJSONArray("data")
                val data = if (dataArray != null) {
                    (0 until dataArray.length()).map { dataArray.optString(it) }
                } else {
                    emptyList()
                }
                VoxLlmRequest(sourcePackage = sourcePackage, task = task, promptText = promptText, data = data)
            } catch (e: Exception) {
                null
            }
        }
    }
}
