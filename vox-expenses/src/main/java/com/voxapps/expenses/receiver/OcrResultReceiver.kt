package com.voxapps.expenses.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.voxapps.attachments.AttachmentEntity
import com.voxapps.attachments.AttachmentFileStore
import com.voxapps.attachments.AttachmentSource
import com.voxapps.expenses.ExpensesApplication
import com.voxapps.expenses.data.ExpensesAttachments
import com.voxapps.expenses.di.ExpensesContainer
import com.voxapps.expenses.domain.llm.ExpenseScanCleanupRequestSender
import com.voxapps.expenses.domain.llm.ExpenseScanRequestSender
import com.voxapps.expenses.domain.llm.LineItemsRescanCombiner
import com.voxapps.expenses.domain.llm.LlmTasks
import com.voxapps.expenses.domain.llm.MultimodalAttachmentResolver
import com.voxapps.ipc.VoxAppsDiscovery
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxOcrResult
import com.voxapps.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private const val TAG = "OcrResultReceiver"

/**
 * Handles incoming raw OCR results from Vision for [LlmTasks.EXPENSE_SCAN_CLEANUP] (create a new
 * expense) / [LlmTasks.EXPENSE_LINEITEMS_RESCAN] (re-read an existing attachment) /
 * [LlmTasks.EXPENSE_ATTACHMENT_CAPTURE] (add a photo to an existing expense) before forwarding OCR
 * text to Commander. Every reply's shape (how many [VoxOcrResult.imageUris], whether [VoxOcrResult.
 * rawText] is present) tells this receiver which of Vision's three capture modes produced it — see
 * [com.voxapps.ipc.VoxOcrRequest.captureMode] — without needing a marker in the task string itself:
 * a null [VoxOcrResult.rawText] always means a batch reply (no OCR ran), and a non-null one always
 * means single or stitch (exactly one already-known text describing everything just captured).
 */
class OcrResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != VoxIpc.ACTION_OCR_RESULT) return
        val result = VoxOcrResult.fromJson(intent.getStringExtra(VoxIpc.EXTRA_OCR_PAYLOAD)) ?: return
        val taskParts = result.task.split(":")
        val baseTask = taskParts.getOrNull(0)
        // "EXPENSE_SCAN_CLEANUP:retry:$expenseId:$dirName:$fileName" — StubRetryBanner picked a
        // manually-added photo instead of the original scan (see
        // ExpenseScanRequestSender.sendHeadlessRetryOcr's doc comment). Same same-named-sibling .txt
        // write as EXPENSE_LINEITEMS_RESCAN below, but waits for the WHOLE current attachment batch
        // (not just one groupId) before combining, and the LLM step routes into the direct-overwrite
        // retry path (retryOfExpenseId), not a review suggestion — a stub has nothing reviewed yet
        // to protect.
        val isRetryWithPhoto = baseTask == LlmTasks.EXPENSE_SCAN_CLEANUP && taskParts.getOrNull(1) == "retry"
        val isAttachmentCapture = baseTask == LlmTasks.EXPENSE_ATTACHMENT_CAPTURE
        // "EXPENSE_SCAN_CLEANUP:pending-create" — Vision's single reply for the main-menu/widget Scan
        // button, any capture mode. A null rawText means batch (see handlePendingScanCreate); a
        // non-null one means single or stitch — either way exactly one already-known text for
        // everything captured, so one new expense gets created directly.
        val isPendingScanCreate = baseTask == LlmTasks.EXPENSE_SCAN_CLEANUP && taskParts.getOrNull(1) == "pending-create"
        // A batch reply (Vision never ran OCR) needs no text to proceed; every other family requires
        // it — computed once here since several guards below need to branch on it.
        val isBatchReply = (isAttachmentCapture || isPendingScanCreate) && result.rawText == null

        if (baseTask != LlmTasks.EXPENSE_SCAN_CLEANUP && baseTask != LlmTasks.EXPENSE_LINEITEMS_RESCAN && !isAttachmentCapture) {
            Logger.d(TAG, "Ignoring unknown OCR task: ${result.task}")
            return
        }

        val rawText = result.rawText
        if (result.status != VoxOcrResult.STATUS_SUCCESS || (rawText.isNullOrBlank() && !isBatchReply)) {
            Logger.w(TAG, "Scan failed or empty: ${result.error}")
            val languageManager = (context.applicationContext as ExpensesApplication).container.languageManager
            Toast.makeText(context, languageManager.getString("scan_save_failed"), Toast.LENGTH_SHORT).show()
            return
        }
        if ((isAttachmentCapture || isPendingScanCreate) && result.imageUris.isEmpty()) {
            Logger.w(TAG, "Capture succeeded with no image — nothing to stage")
            return
        }

        val container = (context.applicationContext as ExpensesApplication).container
        // Rare edge case (the Scan entry point itself already checks this before ever launching
        // Vision) — Commander could still get uninstalled mid-scan. Nothing downstream can do
        // anything without it, so skip straight to telling the user why instead of staging a photo
        // that would never actually become an expense. Attachment capture's own downstream
        // Commander calls (see handleAttachmentCapture below) happen after staging regardless, so
        // this upfront check would only block staging unnecessarily — skipped for that family.
        if (!isAttachmentCapture && !isBatchReply && !VoxAppsDiscovery.isCommanderInstalled(context)) {
            Toast.makeText(context, container.languageManager.getString("commander_required_message"), Toast.LENGTH_SHORT).show()
            return
        }

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (isAttachmentCapture) {
                    handleAttachmentCapture(context, container, taskParts, result.imageUris, rawText, result.rawTexts)
                    return@launch
                }
                if (isPendingScanCreate) {
                    handlePendingScanCreate(context, container, result.imageUris, rawText, result.rawTexts)
                    return@launch
                }
                // Every other task family already required non-blank text to reach this point (see
                // the guard above) — Kotlin just can't track that conditional guarantee across the
                // isAttachmentCapture/isPendingScanCreate branches, so re-bind it non-null here.
                val text = rawText!!
                if (isRetryWithPhoto) {
                    val expenseId = taskParts.getOrNull(2)?.toLongOrNull()
                    val attachDirName = taskParts.getOrNull(3)
                    val attachFileName = taskParts.getOrNull(4)
                    if (expenseId == null || attachDirName == null || attachFileName == null) {
                        Logger.w(TAG, "Retry-with-photo OCR result missing expenseId/dirName/fileName: ${result.task}")
                        return@launch
                    }
                    Logger.d(TAG, "Retry-with-photo OCR result received for expense $expenseId, forwarding to Commander for cleanup")
                    writeOcrTextSibling(context, attachDirName, attachFileName, text)
                    val settings = container.settingsRepository.getSnapshot()
                    val batch = container.attachmentDao.getFor(ExpensesAttachments.RECORD_TYPE, expenseId)
                    val batchFileNames = batch.map { it.fileName }
                    val combinedText = LineItemsRescanCombiner.combineGroupText(context, attachDirName, batchFileNames)
                    if (combinedText == null) {
                        Logger.d(TAG, "Stub retry for expense $expenseId: waiting on remaining photos")
                        return@launch
                    }
                    val attachmentUri = MultimodalAttachmentResolver.resolveArbitraryFile(context, attachDirName, batchFileNames.first(), settings.attachPhotoOnRetry)
                    ExpenseScanCleanupRequestSender.send(context, container, combinedText, imageName = null, retryOfExpenseId = expenseId, attachmentUri = attachmentUri)
                    return@launch
                }
                if (baseTask == LlmTasks.EXPENSE_LINEITEMS_RESCAN) {
                    // The request already named the exact attachment file this OCR run is for (see
                    // ExpenseScanRequestSender.sendHeadlessRescan) — no separate copy of the image is
                    // staged here: the .txt sibling is written right next to that existing file, tied
                    // to it purely by sharing its name, same convention every attachment/receipt uses.
                    val expenseId = taskParts.getOrNull(1)?.toLongOrNull()
                    val attachDirName = taskParts.getOrNull(2)
                    val attachFileName = taskParts.getOrNull(3)
                    if (expenseId == null || attachDirName == null || attachFileName == null) {
                        Logger.w(TAG, "Line-items rescan OCR result missing expenseId/dirName/fileName: ${result.task}")
                        return@launch
                    }
                    Logger.d(TAG, "Rescan OCR result received for expense $expenseId, forwarding to Commander for cleanup")
                    writeOcrTextSibling(context, attachDirName, attachFileName, text)
                    // Same toggle retry uses ("attachPhotoOnRetry"), not attachPhotoOnScan — this is
                    // a photo attached well after the original scan (if there even was one), a
                    // distinct decision from whether to attach on the very first scan.
                    val settings = container.settingsRepository.getSnapshot()

                    // The tapped/captured photo may be one member of an older, already-committed
                    // group whose headless-OCR requests race in parallel (a manual re-rescan tap —
                    // NOT a fresh stitch capture, which already combined its own text before ever
                    // reaching this receiver — see handleAttachmentCapture) — wait until every member
                    // has written its own .txt sibling before firing one combined LLM request. A
                    // group of one (or an ungrouped/batch-independent attachment) sends immediately.
                    val expenseAttachments = container.attachmentDao.getFor(ExpensesAttachments.RECORD_TYPE, expenseId)
                    val groupId = expenseAttachments.firstOrNull { it.fileName == attachFileName }?.groupId
                    val groupMembers = groupId?.let { gid -> expenseAttachments.filter { it.groupId == gid }.sortedBy { it.groupOrder } }

                    if (groupMembers == null || groupMembers.size <= 1) {
                        val attachmentUri = MultimodalAttachmentResolver.resolveArbitraryFile(context, attachDirName, attachFileName, settings.attachPhotoOnRetry)
                        ExpenseScanCleanupRequestSender.sendLineItemsRescan(context, container, expenseId, text, attachmentUri, groupId)
                    } else {
                        // Narrow residual race: if the last two members' OCR replies land close
                        // enough together, both onReceive calls can see "all pages present" and each
                        // fire the combined send once — harmless (identical content, just a redundant
                        // LLM call) rather than the wrong-content race this design exists to prevent.
                        val sent = LineItemsRescanCombiner.combineAndSendRescan(
                            context, container, expenseId, attachDirName, groupMembers.map { it.fileName }, settings.attachPhotoOnRetry, groupId
                        )
                        if (!sent) {
                            Logger.d(TAG, "Group rescan for expense $expenseId: waiting on remaining pages")
                        }
                    }
                } else {
                    Logger.d(TAG, "Ignoring unhandled OCR task: ${result.task}")
                }
            } finally {
                pending.finish()
            }
        }
    }

    /** Writes [rawText] as a same-named .txt sibling next to an existing attachment file — the one
     *  place every task family (fresh-scan create, rescan, retry, attachment capture) stores OCR
     *  text, so [LineItemsRescanCombiner] can always find it by filename alone. */
    private fun writeOcrTextSibling(context: Context, dirName: String, fileName: String, rawText: String) {
        try {
            val dir = File(context.filesDir, dirName).apply { mkdirs() }
            File(dir, fileName.substringBeforeLast('.') + ".txt").writeText(rawText)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to save OCR text sibling for $dirName/$fileName", e)
        }
    }

    /**
     * Attachment-capture task family (see [LlmTasks.EXPENSE_ATTACHMENT_CAPTURE]) — dispatches purely
     * on whether Vision already OCR'd anything (see this class's own doc comment):
     *
     * - [rawText] null (batch — see [com.voxapps.ipc.VoxOcrRequest.CAPTURE_MODE_BATCH]): every photo
     *   is independent, never combined with each other or with the record's existing data. Each gets
     *   staged ungrouped and its own headless-OCR-then-rescan-suggestion via
     *   [ExpenseScanRequestSender.sendHeadlessRescan] — the exact same "own OCR, own LLM JSON per
     *   photo" mechanic the create-record batch flow uses (see [handlePendingScanCreate]), just
     *   producing a review suggestion on this existing expense instead of a new one.
     * - [rawText] non-null (single, or a stitch group — see [com.voxapps.ipc.VoxOcrRequest.
     *   CAPTURE_MODE_STITCH]): exactly one already-known, continuity-verified text describes
     *   everything just staged. A group (2+ photos) is marked [AttachmentSource.STITCHED] — the zoom
     *   view only offers whole-group delete for it, never per-photo, since it's conceptually one
     *   document split across shots (unlike a same-groupId gallery multi-select, which stays
     *   [AttachmentSource.MANUAL] and keeps per-photo delete). Only the group's first file gets the
     *   .txt sibling — see this class's own doc comment on why one text, one file.
     *   Whether to *also* auto-fire an LLM suggestion from that text is gated exactly like a gallery
     *   pick already is (see [com.voxapps.expenses.ui.ExpenseEditScreen]'s `handlePickedUris`): only
     *   when this was the expense's very first attachment AND `autoRescanOnFirstAttachment` is on.
     */
    private suspend fun handleAttachmentCapture(
        context: Context,
        container: ExpensesContainer,
        taskParts: List<String>,
        imageUris: List<String>,
        rawText: String?,
        rawTexts: List<String> = emptyList()
    ) {
        val expenseId = taskParts.getOrNull(1)?.toLongOrNull()
        if (expenseId == null) {
            Logger.w(TAG, "Attachment capture missing expenseId: ${taskParts.joinToString(":")}")
            return
        }
        val wasEmpty = container.attachmentDao.getFor(ExpensesAttachments.RECORD_TYPE, expenseId).isEmpty()
        val stagedFileNames = imageUris.mapNotNull { AttachmentFileStore.stage(context, Uri.parse(it), ExpensesAttachments.DIR) }
        if (stagedFileNames.isEmpty()) {
            Logger.e(TAG, "Failed to stage any attachment capture image")
            withContext(Dispatchers.Main) {
                Toast.makeText(context, container.languageManager.getString("scan_save_failed"), Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (rawText == null) {
            // Vision OCR'd every batch photo itself before replying (see VoxOcrResult.rawTexts' doc
            // comment) — no headless per-photo relaunch needed anymore, so each photo's own
            // rescan-suggestion fires directly off the text Vision already provided.
            stagedFileNames.forEachIndexed { index, fileName ->
                container.attachmentDao.insert(
                    AttachmentEntity(
                        recordType = ExpensesAttachments.RECORD_TYPE,
                        recordId = expenseId,
                        fileName = fileName,
                        source = AttachmentSource.MANUAL,
                        createdAt = System.currentTimeMillis(),
                        groupId = null,
                        groupOrder = 0
                    )
                )
                val text = rawTexts.getOrNull(index)
                if (!text.isNullOrBlank()) {
                    writeOcrTextSibling(context, ExpensesAttachments.DIR, fileName, text)
                    val settings = container.settingsRepository.getSnapshot()
                    val attachmentUri = MultimodalAttachmentResolver.resolveArbitraryFile(context, ExpensesAttachments.DIR, fileName, settings.attachPhotoOnRetry)
                    ExpenseScanCleanupRequestSender.sendLineItemsRescan(context, container, expenseId, text, attachmentUri)
                }
            }
            Logger.d(TAG, "Batch attachment capture staged ${stagedFileNames.size} independent photo(s) for expense $expenseId")
            return
        }

        val groupId = if (stagedFileNames.size > 1) UUID.randomUUID().toString() else null
        val source = if (groupId != null) AttachmentSource.STITCHED else AttachmentSource.MANUAL
        stagedFileNames.forEachIndexed { index, fileName ->
            container.attachmentDao.insert(
                AttachmentEntity(
                    recordType = ExpensesAttachments.RECORD_TYPE,
                    recordId = expenseId,
                    fileName = fileName,
                    source = source,
                    createdAt = System.currentTimeMillis(),
                    groupId = groupId,
                    groupOrder = index
                )
            )
        }
        writeOcrTextSibling(context, ExpensesAttachments.DIR, stagedFileNames.first(), rawText)
        Logger.d(TAG, "Attachment capture staged ${stagedFileNames.size} photo(s) for expense $expenseId (group=$groupId)")

        val settings = container.settingsRepository.getSnapshot()
        if (wasEmpty && settings.autoRescanOnFirstAttachment) {
            val attachmentUri = MultimodalAttachmentResolver.resolveArbitraryFile(context, ExpensesAttachments.DIR, stagedFileNames.first(), settings.attachPhotoOnRetry)
            ExpenseScanCleanupRequestSender.sendLineItemsRescan(context, container, expenseId, rawText, attachmentUri)
        }
    }

    /**
     * Vision's single reply for a Scan-button capture session, any mode (see
     * [com.voxapps.ipc.VoxOcrRequest.captureMode]) — dispatches the same way [handleAttachmentCapture]
     * does:
     * - [rawText] null (batch): every photo becomes its own fully independent expense — stage, then
     *   fire one headless OCR request per photo (see [handlePendingScanBatchPage], which creates each
     *   expense the moment its own text is back, no waiting on/combining with siblings at all).
     * - [rawText] non-null (single, or a stitch session already combined+verified by Vision): stage
     *   everything, write that one text as the (possible) group's first file's sibling, and create
     *   ONE new expense from it directly — no headless round trip needed, Vision already did the OCR.
     */
    private suspend fun handlePendingScanCreate(context: Context, container: ExpensesContainer, imageUris: List<String>, rawText: String?, rawTexts: List<String> = emptyList()) {
        val fileNames = imageUris.mapNotNull { AttachmentFileStore.stage(context, Uri.parse(it), ExpensesAttachments.DIR) }
        if (fileNames.isEmpty()) {
            Logger.e(TAG, "Failed to stage any pending scan image")
            withContext(Dispatchers.Main) {
                Toast.makeText(context, container.languageManager.getString("scan_save_failed"), Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (rawText == null) {
            // Vision OCR'd every batch photo itself before replying (see VoxOcrResult.rawTexts' doc
            // comment) — each photo becomes its own independent expense directly, no headless
            // per-photo relaunch/round-trip needed anymore.
            val settings = container.settingsRepository.getSnapshot()
            fileNames.forEachIndexed { index, fileName ->
                val text = rawTexts.getOrNull(index)
                if (!text.isNullOrBlank()) {
                    writeOcrTextSibling(context, ExpensesAttachments.DIR, fileName, text)
                    val attachmentUri = MultimodalAttachmentResolver.resolveArbitraryFile(context, ExpensesAttachments.DIR, fileName, settings.attachPhotoOnScan)
                    ExpenseScanCleanupRequestSender.sendPendingCreate(context, container, text, listOf(fileName), groupId = null, attachmentUri = attachmentUri)
                }
            }
            Logger.d(TAG, "Pending scan batch: created ${fileNames.count { rawTexts.getOrNull(fileNames.indexOf(it))?.isNotBlank() == true }} expense(s) from ${fileNames.size} photo(s)")
            return
        }

        writeOcrTextSibling(context, ExpensesAttachments.DIR, fileNames.first(), rawText)
        val groupId = if (fileNames.size > 1) UUID.randomUUID().toString() else null
        val settings = container.settingsRepository.getSnapshot()
        val attachmentUri = MultimodalAttachmentResolver.resolveArbitraryFile(context, ExpensesAttachments.DIR, fileNames.first(), settings.attachPhotoOnScan)
        ExpenseScanCleanupRequestSender.sendPendingCreate(context, container, rawText, fileNames, groupId, attachmentUri)
        Logger.d(TAG, "Pending scan (${fileNames.size} photo(s)) staged and creation request sent")
    }
}
