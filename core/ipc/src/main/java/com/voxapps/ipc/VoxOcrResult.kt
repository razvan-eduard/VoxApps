package com.voxapps.ipc

import org.json.JSONObject

/**
 * Vision's async reply to a [VoxOcrRequest]. [imageUri] allows passing a reference to the saved
 * receipt image without bloating the IPC payload.
 */
data class VoxOcrResult(
    val task: String,
    val status: String,
    val rawText: String? = null,
    val imageUri: String? = null,
    val error: String? = null
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("task", task)
        o.put("status", status)
        rawText?.let { o.put("rawText", it) }
        imageUri?.let { o.put("imageUri", it) }
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
                    imageUri = o.optStringOrNull("imageUri"),
                    error = o.optStringOrNull("error")
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
