package com.voxapps.notes.domain.llm

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.voxapps.logging.Logger
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxOcrRequest

private const val TAG = "ScanRequestSender"

/**
 * Launches Vision's activity directly to fulfil a scan request (see [com.voxapps.ipc.VoxOcrRequest])
 * — the "Scanează o notiță" entry point, called both from Notes' own foreground UI and from
 * [com.voxapps.notes.ui.widget.NotesWidgetScanSingleAction] (a Glance `ActionCallback`, which runs
 * with a non-Activity context) — hence [Intent.FLAG_ACTIVITY_NEW_TASK] unconditionally: starting an
 * Activity from a non-Activity context throws without it, and it's a harmless no-op when the caller
 * already is an Activity. Vision replies asynchronously via
 * [com.voxapps.notes.receiver.OcrResultReceiver] with the raw recognized text; Notes takes it from
 * there (LLM cleanup, then save). Mirrors vox-calendar's `CalendarScanRequestSender`.
 */
object ScanRequestSender {
    fun send(context: Context, captureMode: String = VoxOcrRequest.CAPTURE_MODE_SINGLE) {
        val payload = VoxOcrRequest(
            sourcePackage = context.packageName,
            task = LlmTasks.NOTE_SCAN_CLEANUP,
            hint = "Scanning for Notes",
            captureMode = captureMode
        ).toJson()

        Logger.d(TAG, "Launching Vision directly for a scan (captureMode=$captureMode)")
        context.startActivity(
            Intent().apply {
                setClassName(VoxIpc.VISION_PACKAGE, VoxIpc.VISION_ACTIVITY_CLASS)
                putExtra(VoxIpc.EXTRA_OCR_PAYLOAD, payload)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    /**
     * One photo of a batch Scan session (see [VoxOcrRequest.CAPTURE_MODE_BATCH]) — batch capture
     * never runs OCR live, so each photo needs its own headless follow-up. Reuses the exact same
     * [LlmTasks.NOTE_SCAN_CLEANUP] task [com.voxapps.notes.receiver.OcrResultReceiver] already
     * handles for a live scan — Notes' scan flow never tracks which file produced a given text (no
     * attachment-linking on reply, unlike Expenses), so there's nothing extra to thread through the
     * task string; the reply lands in the exact same branch and creates its own independent note.
     * [imageUri] is passed straight through from Vision's own reply, no local copy needed — Vision
     * decodes it via its own FileProvider ownership, not any grant Notes itself was given.
     */
    fun sendHeadlessBatchPageOcr(context: Context, imageUri: Uri) {
        val payload = VoxOcrRequest(
            sourcePackage = context.packageName,
            task = LlmTasks.NOTE_SCAN_CLEANUP,
            hint = "Scanning for Notes",
            returnToCallerOnComplete = true,
            imageUri = imageUri.toString()
        ).toJson()

        Logger.d(TAG, "Launching Vision for a headless batch-page OCR request")
        context.startActivity(
            Intent().apply {
                setClassName(VoxIpc.VISION_PACKAGE, VoxIpc.VISION_ACTIVITY_CLASS)
                putExtra(VoxIpc.EXTRA_OCR_PAYLOAD, payload)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}
