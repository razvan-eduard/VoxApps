package com.voxapps.expenses.domain.llm

import android.content.Context
import com.voxapps.expenses.di.ExpensesContainer
import java.io.File

/**
 * Combines a group's per-photo OCR `.txt` siblings into one `--- Page N ---`-separated text — shared
 * by every place that needs "the whole document's read," never a single page in isolation, since a
 * group represents one physical multi-page document:
 * - [com.voxapps.expenses.receiver.OcrResultReceiver]'s async path (manual rescan tap on an OLDER
 *   group whose members' headless-OCR requests race in parallel — waits until every sibling exists).
 * - [com.voxapps.attachments.ui.rememberVisionCaptureLauncher]'s synchronous "End" path (a live-capture
 *   burst is sequential, so by the time End is tapped every already-taken shot's OCR is guaranteed
 *   complete — no waiting needed).
 * - Stub "retry" (whatever is currently attached to a never-reviewed stub, whole-group if grouped).
 * - A pending (not-yet-saved) multi-shot scan, whose pages have no AttachmentEntity rows to read
 *   fileNames off yet — hence this operates on plain filenames, not entities, so every caller (DB-backed
 *   or not) can use it the same way.
 */
object LineItemsRescanCombiner {

    /** Reads one group member's own `.txt` sibling (written by an earlier/other capture/rescan reply)
     *  — null means that member's OCR text isn't on disk yet, the signal callers wait on. */
    fun readOcrTextSibling(context: Context, dirName: String, fileName: String): String? =
        try {
            val file = File(File(context.filesDir, dirName), fileName.substringBeforeLast('.') + ".txt")
            if (file.exists()) file.readText() else null
        } catch (e: Exception) {
            null
        }

    /** Null if [fileNames] (expected pre-sorted by capture order) is empty, or any member is missing
     *  its `.txt` sibling — the caller decides whether that means "wait and re-check later" (async
     *  path) or "log and bail" (synchronous path, where it should never actually happen). */
    fun combineGroupText(context: Context, dirName: String, fileNames: List<String>): String? {
        if (fileNames.isEmpty()) return null
        val pageTexts = fileNames.map { readOcrTextSibling(context, dirName, it) }
        if (pageTexts.any { it == null }) return null
        return pageTexts.mapIndexed { index, text -> "--- Page ${index + 1} ---\n$text" }.joinToString("\n\n")
    }

    /** The common (non-retry, non-create) case: combine + resolve the first member's photo as the
     *  multimodal attachment (if [attachPhotoToggle] is on) + fire one
     *  [ExpenseScanCleanupRequestSender.sendLineItemsRescan]. Returns false (no-op) if any member's
     *  text is still missing. */
    suspend fun combineAndSendRescan(
        context: Context,
        container: ExpensesContainer,
        expenseId: Long,
        dirName: String,
        fileNames: List<String>,
        attachPhotoToggle: Boolean,
        groupId: String? = null
    ): Boolean {
        val combinedText = combineGroupText(context, dirName, fileNames) ?: return false
        val attachmentUri = MultimodalAttachmentResolver.resolveArbitraryFile(context, dirName, fileNames.first(), attachPhotoToggle)
        ExpenseScanCleanupRequestSender.sendLineItemsRescan(context, container, expenseId, combinedText, attachmentUri, groupId)
        return true
    }
}
