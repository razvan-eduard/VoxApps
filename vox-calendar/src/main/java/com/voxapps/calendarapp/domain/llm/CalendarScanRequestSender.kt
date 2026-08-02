package com.voxapps.calendarapp.domain.llm

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.voxapps.logging.Logger
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxOcrRequest

private const val TAG = "CalendarScanRequestSender"

/**
 * Launches Vision's activity directly to fulfil a scan request (see [com.voxapps.ipc.VoxOcrRequest])
 * — the "Scan an event" entry point, called both from Calendar's own foreground UI and from
 * [com.voxapps.calendarapp.ui.widget.CalendarWidgetScanAction] (a Glance `ActionCallback`, which runs
 * with a non-Activity context) — hence [Intent.FLAG_ACTIVITY_NEW_TASK] unconditionally: starting an
 * Activity from a non-Activity context throws without it, and it's a harmless no-op when the caller
 * already is an Activity (foreground calendar is still in this app's task background-activity-launch
 * restriction applies to the *calling* app's state, not Vision's, either way). Vision replies
 * asynchronously via [com.voxapps.calendarapp.receiver.OcrResultReceiver] with the raw recognized
 * text; Calendar takes it from there (LLM cleanup, then save). Mirrors vox-notes' `ScanRequestSender`.
 */
object CalendarScanRequestSender {
    fun send(context: Context, captureMode: String = VoxOcrRequest.CAPTURE_MODE_SINGLE) {
        val payload = VoxOcrRequest(
            sourcePackage = context.packageName,
            task = LlmTasks.CALENDAR_SCAN_CLEANUP,
            hint = "Scanning for Calendar",
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
     * [LlmTasks.CALENDAR_SCAN_CLEANUP] task [OcrResultReceiver] already handles for a live scan —
     * Calendar's scan flow never tracks which file produced a given text (no attachment-linking on
     * reply, unlike Expenses), so there's nothing extra to thread through the task string; the reply
     * lands in the exact same branch and creates its own independent entry. [imageUri] is passed
     * straight through from Vision's own reply, no local copy needed — Vision decodes it via its own
     * FileProvider ownership, not any grant Calendar itself was given.
     */
    fun sendHeadlessBatchPageOcr(context: Context, imageUri: Uri) {
        val payload = VoxOcrRequest(
            sourcePackage = context.packageName,
            task = LlmTasks.CALENDAR_SCAN_CLEANUP,
            hint = "Scanning for Calendar",
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
