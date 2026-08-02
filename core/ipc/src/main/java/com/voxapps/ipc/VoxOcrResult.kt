package com.voxapps.ipc

import org.json.JSONObject

/**
 * Vision's async reply to a [VoxOcrRequest]. [imageUris] allows passing references to the saved
 * receipt image(s) without bloating the IPC payload — a single-shot reply is a one-element list.
 * For [VoxOcrRequest.CAPTURE_MODE_BATCH], [rawText] is null (no OCR ran — see that mode's own doc
 * comment). For [VoxOcrRequest.CAPTURE_MODE_STITCH], Vision itself has already joined every accepted
 * shot's OCR text into one `rawText` (same `--- Page N ---`-separator convention
 * [com.voxapps.ipc]'s other page-combining call sites use) *before* replying — callers never see
 * per-shot text, only one string and the LLM only ever produces one JSON result, exactly like a
 * plain single-shot reply. This keeps every consumer's "one text in, one record out" assumption
 * intact regardless of which capture mode produced it.
 *
 * [aiImageUri] is a *separate*, smaller copy Vision prepares specifically for LLM attachment
 * (downscaled to the user's configured "photo detail for AI" setting) — kept distinct from
 * [imageUris] (which stays full-resolution, for the caller's own receipt/record display) since a
 * multimodal LLM call doesn't need or want the same resolution a human viewing the record does.
 * Only ever covers the first captured photo (matches today's single-shot semantics) — null when
 * Vision's own "send photo to AI" setting is off, or downscaling failed. Callers must not fall back
 * to an [imageUris] entry for LLM attachment in that case, since that silently defeats the setting.
 */
data class VoxOcrResult(
    val task: String,
    val status: String,
    val rawText: String? = null,
    val imageUris: List<String> = emptyList(),
    val aiImageUri: String? = null,
    val error: String? = null
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("task", task)
        o.put("status", status)
        rawText?.let { o.put("rawText", it) }
        if (imageUris.isNotEmpty()) {
            val arr = org.json.JSONArray()
            imageUris.forEach { arr.put(it) }
            o.put("imageUris", arr)
        }
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
                val arr = o.optJSONArray("imageUris")
                val imageUris = if (arr != null) (0 until arr.length()).map { arr.getString(it) } else emptyList()
                VoxOcrResult(
                    task = task,
                    status = status,
                    rawText = o.optStringOrNull("rawText"),
                    imageUris = imageUris,
                    aiImageUri = o.optStringOrNull("aiImageUri"),
                    error = o.optStringOrNull("error")
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
