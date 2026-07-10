package com.voxapps.ipc

import org.json.JSONObject

/**
 * A request to Vision's generic "scan for me" service: any first-party satellite can ask Vision to
 * run its camera+OCR pipeline and hand back raw recognized text. Mirrors [VoxLlmRequest]'s shape —
 * [task] is an opaque string owned entirely by the caller (Vision never reads or validates it, just
 * echoes it back in [VoxOcrResult]), so a new consumer (e.g. an Expenses app) needs zero Vision-side
 * changes. [hint] is optional free text Vision's UI may show (e.g. "Scanning for Notes"), purely
 * cosmetic — never used for any routing/business logic.
 */
data class VoxOcrRequest(
    val sourcePackage: String,
    val task: String,
    val hint: String? = null
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("sourcePackage", sourcePackage)
        o.put("task", task)
        hint?.let { o.put("hint", it) }
        return o.toString()
    }

    companion object {
        fun fromJson(json: String?): VoxOcrRequest? {
            if (json.isNullOrBlank()) return null
            return try {
                val o = JSONObject(json)
                val sourcePackage = o.optString("sourcePackage").takeIf { it.isNotBlank() } ?: return null
                val task = o.optString("task").takeIf { it.isNotBlank() } ?: return null
                VoxOcrRequest(
                    sourcePackage = sourcePackage,
                    task = task,
                    hint = o.optStringOrNull("hint")
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
