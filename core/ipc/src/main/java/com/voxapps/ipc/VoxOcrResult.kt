package com.voxapps.ipc

import org.json.JSONObject

/**
 * Vision's async reply to a [VoxOcrRequest] — mirrors [VoxLlmResult]'s shape exactly. [rawText] is
 * the raw OCR output only; Vision never classifies or cleans it up (that's the caller's job, done by
 * sending its own follow-up request to Commander's generic LLM hook with its own task/prompt).
 */
data class VoxOcrResult(
    val task: String,
    val status: String,
    val rawText: String? = null,
    val error: String? = null
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("task", task)
        o.put("status", status)
        rawText?.let { o.put("rawText", it) }
        error?.let { o.put("error", it) }
        return o.toString()
    }

    companion object {
        const val STATUS_SUCCESS = "SUCCESS"
        const val STATUS_ERROR = "ERROR"

        fun fromJson(json: String?): VoxOcrResult? {
            if (json.isNullOrBlank()) return null
            return try {
                val o = JSONObject(json)
                val task = o.optString("task").takeIf { it.isNotBlank() } ?: return null
                val status = o.optString("status").takeIf { it.isNotBlank() } ?: return null
                VoxOcrResult(
                    task = task,
                    status = status,
                    rawText = o.optStringOrNull("rawText"),
                    error = o.optStringOrNull("error")
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
