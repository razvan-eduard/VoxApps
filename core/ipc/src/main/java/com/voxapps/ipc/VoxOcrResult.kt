package com.voxapps.ipc

import org.json.JSONObject

/**
 * Vision's async reply to a [VoxOcrRequest]. [imageUri] allows passing a reference to the saved
 * receipt image without bloating the IPC payload.
 *
 * [aiImageUri] is a *separate*, smaller copy Vision prepares specifically for LLM attachment
 * (downscaled to the user's configured "photo detail for AI" setting) — kept distinct from [imageUri]
 * (which stays full-resolution, for the caller's own receipt/record display) since a multimodal LLM
 * call doesn't need or want the same resolution a human viewing the record does. Null when Vision's
 * own "send photo to AI" setting is off, or downscaling failed — callers must not fall back to
 * [imageUri] for LLM attachment in that case, since that silently defeats the user's setting.
 */
data class VoxOcrResult(
    val task: String,
    val status: String,
    val rawText: String? = null,
    val imageUri: String? = null,
    val aiImageUri: String? = null,
    val error: String? = null
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("task", task)
        o.put("status", status)
        rawText?.let { o.put("rawText", it) }
        imageUri?.let { o.put("imageUri", it) }
        aiImageUri?.let { o.put("aiImageUri", it) }
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
                    aiImageUri = o.optStringOrNull("aiImageUri"),
                    error = o.optStringOrNull("error")
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
