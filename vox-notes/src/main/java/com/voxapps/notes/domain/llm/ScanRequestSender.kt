package com.voxapps.notes.domain.llm

import android.content.Context
import android.content.Intent
import com.voxapps.logging.Logger
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxOcrRequest

private const val TAG = "ScanRequestSender"

/**
 * Launches Vision's activity directly to fulfil a scan request (see [com.voxapps.ipc.VoxOcrRequest])
 * — the "Scanează o notiță" entry point. Notes is foreground when the user taps the scan button, so
 * this hits no background-activity-launch restriction (that check is evaluated against the *calling*
 * app's state, not Vision's). Vision replies asynchronously via
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
            }
        )
    }
}
