package com.voxapps.ipc

import org.json.JSONObject

/**
 * Commander's async reply to a [VoxLlmRequest], delivered as an explicit-intent broadcast back to the
 * request's `sourcePackage`. [rawJson] is the LLM's output with only generic cleanup applied (markdown/
 * prose stripped down to the JSON block) — Commander does not validate or understand its shape, that's
 * the calling satellite's concern, dispatched by [task].
 */
data class VoxLlmResult(
    val task: String,
    val status: String,
    val rawJson: String? = null,
    val error: String? = null
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("task", task)
        o.put("status", status)
        rawJson?.let { o.put("rawJson", it) }
        error?.let { o.put("error", it) }
        return o.toString()
    }

    companion object {
        const val STATUS_SUCCESS = "SUCCESS"
        const val STATUS_ERROR = "ERROR"

        /** Lenient parse; returns null if the payload is blank or missing a required field. */
        fun fromJson(json: String?): VoxLlmResult? {
            if (json.isNullOrBlank()) return null
            return try {
                val o = JSONObject(json)
                val task = o.optStringOrNull("task") ?: return null
                val status = o.optStringOrNull("status") ?: return null
                VoxLlmResult(
                    task = task,
                    status = status,
                    rawJson = o.optStringOrNull("rawJson"),
                    error = o.optStringOrNull("error")
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
