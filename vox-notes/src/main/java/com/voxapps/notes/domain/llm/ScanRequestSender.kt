package com.voxapps.notes.domain.llm

import android.content.Context
import android.content.Intent
import com.voxapps.logging.Logger
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxOcrRequest

private const val TAG = "ScanRequestSender"

/**
 * Launches Vision's activity directly to fulfil a scan request (see [com.voxapps.ipc.VoxOcrRequest])
 * — the "Scanează o notiță" entry point, called both from Notes' own foreground UI and from
 * [com.voxapps.notes.ui.widget.NotesWidgetScanAction] (a Glance `ActionCallback`, which runs with a
 * non-Activity context) — hence [Intent.FLAG_ACTIVITY_NEW_TASK] unconditionally: starting an
 * Activity from a non-Activity context throws without it, and it's a harmless no-op when the caller
 * already is an Activity. Vision replies asynchronously via
 * [com.voxapps.notes.receiver.OcrResultReceiver] with the raw recognized text; Notes takes it from
 * there (LLM cleanup, then save).
 */
object ScanRequestSender {
    fun send(context: Context) {
        val payload = VoxOcrRequest(
            sourcePackage = context.packageName,
            task = LlmTasks.NOTE_SCAN_CLEANUP,
            hint = "Scanning for Notes"
        ).toJson()

        Logger.d(TAG, "Launching Vision directly for a scan")
        context.startActivity(
            Intent().apply {
                setClassName(VoxIpc.VISION_PACKAGE, VoxIpc.VISION_ACTIVITY_CLASS)
                putExtra(VoxIpc.EXTRA_OCR_PAYLOAD, payload)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}
