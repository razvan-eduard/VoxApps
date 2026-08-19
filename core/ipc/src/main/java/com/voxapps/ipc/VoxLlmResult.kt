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
    val error: String? = null,
    /**
     * What was put to the model, echoed back, when the satellite has no other way to know it.
     *
     * Set only where Commander composed the input itself — it fills a cached
     * [VoxSatelliteSchema.promptTemplate] from its own decomposition, so the satellite never saw the
     * text it is now being answered about. Without this, anything a satellite could have established
     * from that text on its own is unreachable by the time the answer arrives, and every field has to
     * be taken from the model whether or not a rule could have settled it.
     *
     * Null on the ordinary path, and correctly so: there the satellite composed the request, and its
     * own durable queue still holds the input under the request id (see
     * [VoxLlmRequestQueue.originalInput]). Echoing it back would be sending a satellite its own
     * words, and on a scan those words are a whole page.
     */
    val input: String? = null
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("task", task)
        o.put("status", status)
        rawJson?.let { o.put("rawJson", it) }
        error?.let { o.put("error", it) }
        input?.let { o.put("input", it) }
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
                    error = o.optStringOrNull("error"),
                    // Absent from an older Commander's reply, which reads as "I did not compose
                    // this" — the same thing it means on the ordinary path.
                    input = o.optStringOrNull("input")
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
