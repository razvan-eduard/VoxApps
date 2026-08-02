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
    val hint: String? = null,
    // When true, Vision relaunches sourcePackage's own launcher activity (bringing its existing
    // task to front, not creating a new instance) right before finishing itself — for a caller
    // whose own foreground UI is what asked for the scan, so the user lands back where they were
    // instead of wherever Android's back stack happens to resolve to. Left false (the default) for
    // a caller that wasn't itself in the foreground when it asked (e.g. a home-screen widget) —
    // there's nothing meaningful to "return" to in that case.
    val returnToCallerOnComplete: Boolean = false,
    // When set, Vision skips the live camera entirely and runs OCR against this already-existing
    // content:// URI instead — for a caller that already has an image (e.g. a photo attached to a
    // record well after it was created) and just needs text out of it, with no user-facing camera UI
    // at all. The caller must grantUriPermission(VoxIpc.VISION_PACKAGE, uri, FLAG_GRANT_READ_URI_PERMISSION)
    // before sending — this field is a plain string extra, not Intent data/ClipData, so Android's
    // permission system never sees it (mirrors why VoxOcrResult.imageUri/aiImageUri need their own
    // explicit grant on the reply side — see OcrResultSender). Null (the default) is today's
    // camera-capture behavior, unchanged.
    val imageUri: String? = null,
    // When false, Vision still captures/crops/stages a photo (live or headless) but skips running it
    // through the OCR engine entirely — VoxOcrResult.rawText comes back null. For a caller that only
    // wants Vision's camera quality (flash/auto-capture/document-crop) on a photo it has no use for
    // text from. Defaults true so every existing caller's behavior is unchanged. Only meaningful for
    // [CAPTURE_MODE_SINGLE] — [captureMode]'s own doc comment covers OCR timing for the other two.
    val produceOCR: Boolean = true,
    // One of [CAPTURE_MODE_SINGLE] (default)/[CAPTURE_MODE_BATCH]/[CAPTURE_MODE_STITCH] — which of
    // Vision's three live-camera capture loops to run:
    // - SINGLE: today's plain one-shot capture, [produceOCR] applies as documented above.
    // - BATCH: capture-only loop — crop each shot, hold it, offer "add another/done" entirely inside
    //   Vision, OCR forced off for every shot (a live capture loop should be a fast tap-tap-tap
    //   session, not paced by an OCR pass after every photo). Replies once, on "done", with every
    //   captured photo in [VoxOcrResult.imageUris] and no text — OCR, if wanted, is the caller's own
    //   separate headless-request step afterward (see [imageUri]), and each photo is meant to become
    //   its own independent record, never combined with the others.
    // - STITCH: several shots meant to become ONE record (e.g. a long document photographed in
    //   overlapping segments) — OCR runs live after every shot, and each shot after the first is
    //   checked for plausible text continuity against the previous *accepted* shot (fuzzy word-overlap,
    //   strictness is a Vision-side setting) before being accepted; a failed check prompts an explicit
    //   retake-or-use-anyway choice, never a silent drop. Replies once, on "done", with every accepted
    //   photo's URI in [VoxOcrResult.imageUris] and its already-OCR'd, already-verified text in the
    //   parallel [VoxOcrResult.rawTexts] — no further OCR needed by the caller.
    // Meaningless combined with [imageUri] (the headless, no-camera path) — only applies to a live
    // camera session. Defaults to [CAPTURE_MODE_SINGLE] so every existing caller is unaffected.
    val captureMode: String = CAPTURE_MODE_SINGLE
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("sourcePackage", sourcePackage)
        o.put("task", task)
        hint?.let { o.put("hint", it) }
        o.put("returnToCallerOnComplete", returnToCallerOnComplete)
        imageUri?.let { o.put("imageUri", it) }
        o.put("produceOCR", produceOCR)
        o.put("captureMode", captureMode)
        return o.toString()
    }

    companion object {
        const val CAPTURE_MODE_SINGLE = "single"
        const val CAPTURE_MODE_BATCH = "batch"
        const val CAPTURE_MODE_STITCH = "stitch"

        fun fromJson(json: String?): VoxOcrRequest? {
            if (json.isNullOrBlank()) return null
            return try {
                val o = JSONObject(json)
                val sourcePackage = o.optString("sourcePackage").takeIf { it.isNotBlank() } ?: return null
                val task = o.optString("task").takeIf { it.isNotBlank() } ?: return null
                VoxOcrRequest(
                    sourcePackage = sourcePackage,
                    task = task,
                    hint = o.optStringOrNull("hint"),
                    returnToCallerOnComplete = o.optBoolean("returnToCallerOnComplete", false),
                    imageUri = o.optStringOrNull("imageUri"),
                    produceOCR = o.optBoolean("produceOCR", true),
                    captureMode = o.optString("captureMode").takeIf { it.isNotBlank() } ?: CAPTURE_MODE_SINGLE
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
