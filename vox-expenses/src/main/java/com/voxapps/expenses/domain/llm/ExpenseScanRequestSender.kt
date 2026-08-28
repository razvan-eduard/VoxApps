package com.voxapps.expenses.domain.llm

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.voxapps.attachments.AttachmentFileStore
import com.voxapps.attachments.PdfPageRenderer
import com.voxapps.expenses.data.ExpensesAttachments
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxOcrRequest
import com.voxapps.logging.Logger

private const val TAG = "ExpenseScanRequestSender"

/**
 * Headless (existing-image, no camera UI) Vision requests for Expenses — rescans/retries of a photo
 * already staged on disk, both triggered from Expenses' own foreground screen (the attachments
 * section's rescan icon / "Retry cleanup" banner). Live-camera captures for a new scan or an
 * attachment go through [com.voxapps.attachments.VisionAttachmentCapture]/
 * `rememberVisionCaptureLauncher` instead (see [com.voxapps.expenses.ui.ExpensesScreen]/
 * [com.voxapps.expenses.ui.ExpenseEditScreen]).
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
     * [sendHeadlessRescan] for a whole group in ONE request — never one request per member: Vision's
     * activity is singleTask and a second headless launch cancels the one in flight (see
     * [com.voxapps.ipc.VoxOcrRequest.imageUris]), so a fanned-out group loses every page but the
     * last. The task's file segment carries the comma-joined member names in request order;
     * [com.voxapps.expenses.receiver.OcrResultReceiver] writes each page's `.txt` sibling from the
     * batch-shaped reply, leaving a page whose fresh read failed on its previous text.
     *
     * skipCrop, always: the members this path mostly carries are already flat — a picked PDF's
     * rendered pages by construction, stitch members and capture-reply copies because Vision cropped
     * them before they were ever staged. Re-cropping a flat render is not just useless, it is the
     * measured failure: edge detection over a white full-bleed page hands the OCR a degenerate
     * region (native inference failure on one page; a 3-page sequential run ground an emulator into
     * a watchdog reboot). The one member kind that genuinely arrives uncropped — a gallery
     * multi-select photo — trades crop quality for that stability here; its single-file rescan keeps
     * the crop.
     */
    fun sendHeadlessGroupRescan(context: Context, expenseId: Long, dirName: String, fileNames: List<String>) {
        val uris = fileNames.map {
            AttachmentFileStore.uriFor(context, ExpensesAttachments.FILE_PROVIDER_AUTHORITY, dirName, it)
        }
        uris.forEach { context.grantUriPermission(VoxIpc.VISION_PACKAGE, it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val payload = VoxOcrRequest(
            sourcePackage = context.packageName,
            task = "${LlmTasks.EXPENSE_LINEITEMS_RESCAN}:$expenseId:$dirName:${fileNames.joinToString(",")}",
            hint = "Rescanning attached photos",
            returnToCallerOnComplete = true,
            imageUris = uris.map { it.toString() },
            skipCrop = true,
            tableMode = true
        ).toJson()

        Logger.d(TAG, "Launching Vision for a headless group rescan (${fileNames.size} member(s), expense $expenseId)")
        context.startActivity(
            Intent().apply {
                setClassName(VoxIpc.VISION_PACKAGE, VoxIpc.VISION_ACTIVITY_CLASS)
                putExtra(VoxIpc.EXTRA_OCR_PAYLOAD, payload)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
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
     * For a photo or PDF the user picked from local storage to become a NEW expense (see
     * [PendingFileScanTask]). Stages the picked file under its real extension, renders a PDF's pages
     * into staged images, and sends ONE headless Vision request carrying every page — one request
     * because a second headless launch cancels the one in flight (see
     * [com.voxapps.ipc.VoxOcrRequest.imageUris]), and because the picked SAF URI's read grant can't
     * be re-granted onward, so Vision must be handed our own staged copies. Rendered PDF pages skip
     * Vision's document crop (flat, full-bleed — edge detection could only trim content); a picked
     * photo keeps it, same as a rescan. Blocking IO — call off the main thread.
     *
     * Returns false when the file couldn't be staged or no page rendered (already cleaned up) —
     * the caller owns telling the user.
     */
    fun sendHeadlessFileCreate(context: Context, sourceUri: Uri): Boolean {
        val original = AttachmentFileStore.stage(context, sourceUri, ExpensesAttachments.DIR) ?: return false
        val isPdf = original.endsWith(".pdf", ignoreCase = true)
        val pages = if (isPdf) {
            PdfPageRenderer.renderToStagedJpegs(context, ExpensesAttachments.DIR, original) ?: run {
                AttachmentFileStore.delete(context, ExpensesAttachments.DIR, original)
                return false
            }
        } else {
            listOf(original)
        }
        val pageUris = pages.map {
            AttachmentFileStore.uriFor(context, ExpensesAttachments.FILE_PROVIDER_AUTHORITY, ExpensesAttachments.DIR, it)
        }
        pageUris.forEach { context.grantUriPermission(VoxIpc.VISION_PACKAGE, it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val payload = VoxOcrRequest(
            sourcePackage = context.packageName,
            task = PendingFileScanTask.build(original, pages),
            returnToCallerOnComplete = true,
            imageUris = pageUris.map { it.toString() },
            skipCrop = isPdf,
            tableMode = true
        ).toJson()

        Logger.d(TAG, "Launching Vision for a picked-file scan (${pages.size} page(s), pdf=$isPdf)")
        context.startActivity(
            Intent().apply {
                setClassName(VoxIpc.VISION_PACKAGE, VoxIpc.VISION_ACTIVITY_CLASS)
                putExtra(VoxIpc.EXTRA_OCR_PAYLOAD, payload)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        return true
    }

    private fun launchHeadlessOcr(context: Context, imageUri: Uri, task: String) {
        context.grantUriPermission(VoxIpc.VISION_PACKAGE, imageUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val payload = VoxOcrRequest(
            sourcePackage = context.packageName,
            task = task,
            hint = "Rescanning attached photo",
            returnToCallerOnComplete = true,
            imageUri = imageUri.toString(),
            tableMode = true
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
