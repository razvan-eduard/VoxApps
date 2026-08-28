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

    /** The group members OCR can actually run on. A picked file's original PDF sits in its group
     *  beside the page images that carry its text — it can never have a `.txt` sibling of its own,
     *  and handing it to Vision's bitmap decode would only fail, so every re-OCR path reads through
     *  this filter. */
    fun ocrEligible(fileNames: List<String>): List<String> =
        fileNames.filterNot { it.endsWith(".pdf", ignoreCase = true) }

    /** One document out of per-page OCR texts, in page order: blank pages are skipped but keep their
     *  page numbers, a single non-blank page is returned verbatim (same shape a single-shot scan
     *  produces), and no text at all is null. */
    fun combinePageTexts(texts: List<String>): String? {
        val pages = texts.mapIndexedNotNull { index, text ->
            text.takeIf { it.isNotBlank() }?.let { (index + 1) to it }
        }
        return when {
            pages.isEmpty() -> null
            pages.size == 1 -> pages.single().second
            else -> pages.joinToString("\n\n") { (number, text) -> "--- Page $number ---\n$text" }
        }
    }

    /** Reads one group member's own `.txt` sibling (written by an earlier/other capture/rescan reply)
     *  — null means that member's OCR text isn't on disk yet, the signal callers wait on. */
    fun readOcrTextSibling(context: Context, dirName: String, fileName: String): String? =
        try {
            val file = File(File(context.filesDir, dirName), fileName.substringBeforeLast('.') + ".txt")
            if (file.exists()) file.readText() else null
        } catch (e: Exception) {
            null
        }

    /** Null if [fileNames] (expected pre-sorted by capture order) has no OCR-eligible member, or any
     *  eligible member is missing its `.txt` sibling — the caller decides whether that means "wait
     *  and re-check later" (async path) or "log and bail" (synchronous path, where it should never
     *  actually happen). A PDF member is not waited on — see [ocrEligible]. */
    fun combineGroupText(context: Context, dirName: String, fileNames: List<String>): String? {
        val eligible = ocrEligible(fileNames)
        if (eligible.isEmpty()) return null
        val pageTexts = eligible.map { readOcrTextSibling(context, dirName, it) }
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
        val attachmentUri = MultimodalAttachmentResolver.resolveArbitraryFile(context, dirName, ocrEligible(fileNames).first(), attachPhotoToggle)
        ExpenseScanCleanupRequestSender.sendLineItemsRescan(context, container, expenseId, combinedText, attachmentUri, groupId)
        return true
    }
}
