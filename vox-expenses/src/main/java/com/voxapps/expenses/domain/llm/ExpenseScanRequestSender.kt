package com.voxapps.expenses.domain.llm

import android.content.Context
import android.content.Intent
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxOcrRequest
import com.voxapps.logging.Logger

private const val TAG = "ExpenseScanRequestSender"

/**
 * Launches Vision's activity directly to fulfil a scan request (see [com.voxapps.ipc.VoxOcrRequest])
 * — the "Scan receipt" entry point (mirrors vox-notes' ScanRequestSender), called both from Expenses'
 * own foreground UI and from [com.voxapps.expenses.ui.widget.ExpensesWidgetScanAction] (a Glance
 * `ActionCallback`, which runs with a non-Activity context) — hence [Intent.FLAG_ACTIVITY_NEW_TASK]
 * unconditionally: starting an Activity from a non-Activity context throws without it, and it's a
 * harmless no-op when the caller already is an Activity. Vision replies asynchronously via
 * [com.voxapps.expenses.receiver.OcrResultReceiver] with the raw recognized text; Expenses takes it
 * from there (LLM cleanup, then save) — no vox-vision changes needed, its pending-request path is
 * already domain-agnostic.
 */
object ExpenseScanRequestSender {
    fun send(context: Context) {
        val payload = VoxOcrRequest(
            sourcePackage = context.packageName,
            task = LlmTasks.EXPENSE_SCAN_CLEANUP,
            hint = "Scanning for Expenses"
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
