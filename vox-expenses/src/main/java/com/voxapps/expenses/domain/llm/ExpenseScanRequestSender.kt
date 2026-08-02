package com.voxapps.expenses.domain.llm

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxOcrRequest
import com.voxapps.logging.Logger

private const val TAG = "ExpenseScanRequestSender"

/**
 * Headless (existing-image, no camera UI) Vision requests for Expenses — rescans/retries of a photo
 * already staged on disk. Live-camera captures for a new scan or an attachment now go through
 * [com.voxapps.attachments.VisionAttachmentCapture]/`rememberVisionCaptureLauncher` instead (see
 * [com.voxapps.expenses.ui.ExpensesScreen]/[com.voxapps.expenses.ui.ExpenseEditScreen]); this object
 * is purely for the headless follow-up steps [com.voxapps.expenses.receiver.OcrResultReceiver] fires
 * once a batch/stitch reply lands.
 */
object ExpenseScanRequestSender {
    /**
     * For a photo already attached to an already-saved expense (see [LlmTasks.EXPENSE_LINEITEMS_RESCAN])
     * — unlike [send], there's no fresh photo to take, so Vision runs OCR directly against [imageUri]
     * instead of opening its live camera (see [com.voxapps.ipc.VoxOcrRequest.imageUri]). [imageUri]'s
     * permission grant is the caller's job: it travels as a plain JSON string field, not Intent
     * data/ClipData, so Android's own URI-grant propagation never sees it — mirrors why
     * [MultimodalAttachmentResolver] explicitly grants Commander access before attaching a photo to an
     * LLM call. Always called from Expenses' own foreground screen (the attachments section), hence
     * `returnToCallerOnComplete = true` unconditionally, unlike [send]'s widget-aware default.
     *
     * [dirName]/[fileName] identify the exact attachment file [imageUri] resolves to — threaded through
     * the task string so [com.voxapps.expenses.receiver.OcrResultReceiver] can write the OCR result's
     * text as a same-named sibling right next to that existing file (no separate staged copy needed —
     * the shared filename alone ties the two together, same convention a fresh scan's own receipt/.txt
     * pair already uses).
     */
    fun sendHeadlessRescan(context: Context, expenseId: Long, dirName: String, fileName: String, imageUri: Uri) {
        launchHeadlessOcr(context, imageUri, task = "${LlmTasks.EXPENSE_LINEITEMS_RESCAN}:$expenseId:$dirName:$fileName")
    }

    /**
     * For [com.voxapps.expenses.ui.ExpenseEditScreen]'s "Retry cleanup" banner, when the user picks a
     * manually-added photo instead of the original scan — that photo has never been through Vision's
     * OCR either (same situation as [sendHeadlessRescan]), so this runs the identical headless-OCR
     * step. The difference is entirely on the receiving end: [com.voxapps.expenses.receiver.OcrResultReceiver]
     * routes this task family into the same direct-overwrite retry path [imageName]-based retry
     * already uses (a stub has nothing reviewed yet to protect, unlike [sendHeadlessRescan]'s target),
     * not a review suggestion.
     */
    fun sendHeadlessRetryOcr(context: Context, expenseId: Long, dirName: String, fileName: String, imageUri: Uri) {
        launchHeadlessOcr(context, imageUri, task = "${LlmTasks.EXPENSE_SCAN_CLEANUP}:retry:$expenseId:$dirName:$fileName")
    }

    /**
     * One photo of a batch Scan session's per-photo OCR step (see
     * [com.voxapps.expenses.receiver.OcrResultReceiver.handlePendingScanCreate]'s batch branch) —
     * headless OCR against an already-staged (batch capture never runs OCR live — see
     * [com.voxapps.ipc.VoxOcrRequest.CAPTURE_MODE_BATCH]) photo. No expense id and no group exist:
     * each batch entry becomes its own fully independent expense the moment this reply lands, so the
     * task string only needs to carry [fileName] — nothing to wait for or combine.
     */
    fun sendHeadlessPendingBatchPageOcr(context: Context, fileName: String, imageUri: Uri) {
        launchHeadlessOcr(context, imageUri, task = "${LlmTasks.EXPENSE_SCAN_CLEANUP}:pending-batch-page:$fileName")
    }

    private fun launchHeadlessOcr(context: Context, imageUri: Uri, task: String) {
        context.grantUriPermission(VoxIpc.VISION_PACKAGE, imageUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val payload = VoxOcrRequest(
            sourcePackage = context.packageName,
            task = task,
            hint = "Rescanning attached photo",
            returnToCallerOnComplete = true,
            imageUri = imageUri.toString()
        ).toJson()

        Logger.d(TAG, "Launching Vision for a headless OCR request (task=$task)")
        context.startActivity(
            Intent().apply {
                setClassName(VoxIpc.VISION_PACKAGE, VoxIpc.VISION_ACTIVITY_CLASS)
                putExtra(VoxIpc.EXTRA_OCR_PAYLOAD, payload)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}
