package com.voxapps.calendarapp.domain.llm

import android.content.Context
import android.content.Intent
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
    fun send(context: Context) {
        val payload = VoxOcrRequest(
            sourcePackage = context.packageName,
            task = LlmTasks.CALENDAR_SCAN_CLEANUP,
            hint = "Scanning for Calendar"
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
