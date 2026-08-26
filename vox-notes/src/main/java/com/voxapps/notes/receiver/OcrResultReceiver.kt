package com.voxapps.notes.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.voxapps.attachments.AttachmentEntity
import com.voxapps.attachments.AttachmentFileStore
import com.voxapps.attachments.AttachmentSource
import com.voxapps.ipc.VoxAppsDiscovery.COMMANDER_PACKAGE
import com.voxapps.logging.Logger
import com.voxapps.notes.data.NotesAttachments
import com.voxapps.notes.data.preferences.NotesSettings
import com.voxapps.ipc.VoxAppsDiscovery
import com.voxapps.ipc.VoxCapabilityClient
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxOcrResult
import com.voxapps.notes.NotesApplication
import com.voxapps.notes.di.NotesContainer
import com.voxapps.notes.domain.llm.LlmTasks
import com.voxapps.recordflow.RecordFlow
import com.voxapps.notes.domain.llm.NoteScanFlow
import com.voxapps.notes.domain.llm.NoteScanCleanupPromptBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

private const val TAG = "OcrResultReceiver"
private const val NOTES_FILE_PROVIDER_AUTHORITY = com.voxapps.notes.data.NotesAttachments.FILE_PROVIDER_AUTHORITY

/**
 * Notes' end of Vision's generic OCR hook — two distinct task families, dispatched by prefix (not
 * exact match, so a colon-suffixed task like an attachment capture's `:$noteId` still routes
 * correctly): [LlmTasks.NOTE_SCAN_CLEANUP] (the "Scan a note" flow — forwards the raw scanned
 * text to Commander's generic LLM hook for cleanup; the actual note gets created when that cleanup
 * reply lands in [LlmResultReceiver]) and [LlmTasks.NOTE_ATTACHMENT_CAPTURE] (adding a photo to a
 * note — always produceOCR=false here, so this just stages the photo(s) and commits AttachmentEntity
 * row(s), no LLM round-trip at all). Guarded by the shared `com.voxapps.vox.permission.OCR_RESULT`
 * signature permission (declared once in `:core:ipc`). Mirrors vox-calendar's `OcrResultReceiver`.
 */
class OcrResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != VoxIpc.ACTION_OCR_RESULT) return
        val result = VoxOcrResult.fromJson(intent.getStringExtra(VoxIpc.EXTRA_OCR_PAYLOAD)) ?: return
        val taskParts = result.task.split(":")
        val baseTask = taskParts.getOrNull(0)

        if (baseTask == LlmTasks.NOTE_ATTACHMENT_CAPTURE) {
            if (result.status != VoxOcrResult.STATUS_SUCCESS || result.imageUris.isEmpty()) {
                Logger.w(TAG, "Attachment capture succeeded with no image — nothing to stage")
                return
            }
            val container = (context.applicationContext as NotesApplication).container
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    handleAttachmentCapture(context, container, taskParts, result.imageUris)
                } finally {
                    pending.finish()
                }
            }
            return
        }

        if (baseTask != LlmTasks.NOTE_SCAN_CLEANUP) {
            Logger.d(TAG, "Ignoring unknown OCR task: ${result.task}")
            return
        }

        // A batch Scan session's single reply (see VoxOcrRequest.CAPTURE_MODE_BATCH) — Vision now
        // OCRs every photo itself before replying (see VoxOcrResult.rawTexts' doc comment for why:
        // the old design sent imageUris only and expected this receiver to relaunch Vision once per
        // photo afterward, which Android's background-execution restrictions silently block). Each
        // (imageUri, text) pair becomes its own independent note, same forwarding-to-Commander logic
        // as a single-shot reply, just looped.
        if (result.status == VoxOcrResult.STATUS_SUCCESS && result.rawText == null && result.rawTexts.isNotEmpty()) {
            val container = (context.applicationContext as NotesApplication).container
            if (!VoxAppsDiscovery.isCommanderInstalled(context)) {
                Toast.makeText(context, container.languageManager.getString("commander_required_message"), Toast.LENGTH_SHORT).show()
                return
            }
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    result.imageUris.zip(result.rawTexts).forEach { (imageUri, text) ->
                        if (text.isNotBlank()) forwardScanToCommander(context, container, text, imageUri)
                    }
                    Logger.d(TAG, "Batch scan: created ${result.rawTexts.count { it.isNotBlank() }} note(s) from ${result.imageUris.size} photo(s)")
                } finally {
                    pending.finish()
                }
            }
            return
        }

        val rawText = result.rawText
        if (result.status != VoxOcrResult.STATUS_SUCCESS || rawText.isNullOrBlank()) {
            Logger.w(TAG, "Scan failed or empty: ${result.error}")
            // Unconditional (not gated behind voiceSaveToastEnabled, which is opt-in and off by
            // default) — a failure toast is the only signal the user has that the scan didn't work,
            // unlike a success which is also visible as a new list item.
            val languageManager = (context.applicationContext as NotesApplication).container.languageManager
            Toast.makeText(context, languageManager.getString("scan_save_failed"), Toast.LENGTH_SHORT).show()
            return
        }

        val container = (context.applicationContext as NotesApplication).container
        // Rare edge case (the Scan entry point itself already checks this before ever launching
        // Vision) — Commander could still get uninstalled mid-scan. Nothing downstream can do
        // anything without it, so skip straight to telling the user why.
        if (!VoxAppsDiscovery.isCommanderInstalled(context)) {
            Toast.makeText(context, container.languageManager.getString("commander_required_message"), Toast.LENGTH_SHORT).show()
            return
        }

        Logger.d(TAG, "Scan result received, forwarding to Commander for cleanup")
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                forwardScanToCommander(context, container, rawText, result.imageUris.firstOrNull(), result.aiImageUri)
            } finally {
                pending.finish()
            }
        }
    }

    /** One scanned photo's text -> one new note, forwarded to Commander's generic LLM hook for
     *  cleanup (the note itself gets created once that cleanup reply lands in [LlmResultReceiver]).
     *  Shared by a single/stitch reply (exactly one call) and a batch reply (looped, one call per
     *  accepted photo — see the batch branch above). */
    private suspend fun forwardScanToCommander(
        context: Context,
        container: com.voxapps.notes.di.NotesContainer,
        rawText: String,
        imageUri: String?,
        aiImageUri: String? = null
    ) {
        val existingCategories = container.notesRepository.categories.first().map { it.name }
        val settings = container.settingsRepository.getSnapshot()
        val language = settings.language

        // No receipt-record equivalent here (unlike Expenses) and no retry mechanism, so the
        // AI-attachment copy only ever needs to live long enough for this one call — staged
        // into cache, not persisted. Gated on our own attachPhotoOnScan toggle (checked here)
        // and Vision having actually provided a downscaled copy in the first place (its own
        // "send photo to AI" setting); the multimodal-engine check happens inside the grant.
        // A batch reply's per-photo AI copy isn't currently produced by Vision (aiImageUri is
        // null for those calls) — same limitation the old headless-relaunch design had.
        val attachmentUri = if (settings.attachPhotoOnScan) {
            aiImageUri?.let { aiUriString -> stageAndGrantAiCopy(context, aiUriString) }
        } else null

        // Unconditionally staged (regardless of scanImageRetention) — mirrors vox-expenses'
        // OcrResultReceiver, and for the same reason: we don't yet know success/failure, and
        // Vision's own grant on imageUri may not outlive this call. LlmResultReceiver decides
        // whether to keep it (create an AttachmentEntity) or delete it once the real outcome is
        // known, per NotesSettings.scanImageRetention.
        val stagedImageName = imageUri?.let { uriString ->
            AttachmentFileStore.stage(context, Uri.parse(uriString), NotesAttachments.DIR)
        }
        // One flow, whatever the level: the shared template reads what the device can establish,
        // decides who answers the rest, and either writes the note, keeps it for a person, or asks.
        // The offline path is not a branch here any more — it is this same call with a level that
        // asks nothing.
        val flow = NoteScanFlow(context, container, stagedImageName)
        val taskWithMeta = if (stagedImageName != null) {
            "${LlmTasks.NOTE_SCAN_CLEANUP}:$stagedImageName"
        } else {
            LlmTasks.NOTE_SCAN_CLEANUP
        }
        RecordFlow.dispatch(
            spec = flow,
            input = rawText,
            level = NotesSettings.scanLevelOf(settings.scanLlmLevel)
        ) { _, prompt ->
            // The prompt arrives already composed by the flow; delivery stays here, where the
            // durable queue is.
            container.pendingLlmRequestQueue.enqueueAndSend(
                context = context,
                sourcePackage = context.packageName,
                task = taskWithMeta,
                promptText = prompt,
                targetPackage = COMMANDER_PACKAGE,
                data = listOf(rawText),
                attachmentUri = attachmentUri
            )
        }
    }

    /** Stages every returned photo and commits it as a real AttachmentEntity row — no OCR text
     *  involved at all, Notes never requests it for attachments (see [LlmTasks.
     *  NOTE_ATTACHMENT_CAPTURE]'s doc comment). A 2+ photo reply (a stitch group — see
     *  [com.voxapps.ipc.VoxOcrRequest.CAPTURE_MODE_STITCH]) shares one groupId and is marked
     *  [AttachmentSource.STITCHED] so the zoom view only offers whole-group delete for it; a batch
     *  reply's photos are independent (ungrouped, [AttachmentSource.MANUAL]) — mirrors vox-calendar's
     *  OcrResultReceiver.handleAttachmentCapture. Task string shape:
     *  "$NOTE_ATTACHMENT_CAPTURE:$noteId". */
    private suspend fun handleAttachmentCapture(context: Context, container: NotesContainer, taskParts: List<String>, imageUris: List<String>) {
        val noteId = taskParts.getOrNull(1)?.toLongOrNull()
        if (noteId == null) {
            Logger.w(TAG, "Attachment capture missing noteId: ${taskParts.joinToString(":")}")
            return
        }
        val stagedFileNames = imageUris.mapNotNull { AttachmentFileStore.stage(context, Uri.parse(it), NotesAttachments.DIR) }
        if (stagedFileNames.isEmpty()) {
            Logger.e(TAG, "Failed to stage any attachment capture image")
            return
        }
        val groupId = if (stagedFileNames.size > 1) UUID.randomUUID().toString() else null
        val source = if (groupId != null) AttachmentSource.STITCHED else AttachmentSource.MANUAL
        stagedFileNames.forEachIndexed { index, fileName ->
            container.attachmentDao.insert(
                AttachmentEntity(
                    recordType = NotesAttachments.RECORD_TYPE,
                    recordId = noteId,
                    fileName = fileName,
                    source = source,
                    createdAt = System.currentTimeMillis(),
                    groupId = groupId,
                    groupOrder = index
                )
            )
        }
        Logger.d(TAG, "Attachment capture staged ${stagedFileNames.size} photo(s) for note $noteId (group=$groupId)")
    }

    /** Copies Vision's already-downscaled AI-attachment image (a URI Vision granted *this* app read
     *  access to, not something this app owns) into our own cache and re-shares it via our own
     *  FileProvider — a plain read grant on someone else's FileProvider URI can't be re-granted
     *  onward to a third app (Commander), only the URI's actual owner can do that, so a local copy is
     *  required regardless of how short-lived it is. Fails safe to null (no attachment) on any error,
     *  or if Commander's configured engine turns out not to be multimodal after all. */
    private suspend fun stageAndGrantAiCopy(context: Context, aiUriString: String): String? {
        if (!VoxCapabilityClient.isMultimodal(context)) return null
        return try {
            val sourceUri = Uri.parse(aiUriString)
            val cacheDir = File(context.cacheDir, "ai_scans").apply { mkdirs() }
            val targetFile = File(cacheDir, "scan_${java.util.UUID.randomUUID()}.jpg")
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(targetFile).use { output -> input.copyTo(output) }
            } ?: return null
            val uri = FileProvider.getUriForFile(context, NOTES_FILE_PROVIDER_AUTHORITY, targetFile)
            context.grantUriPermission(VoxAppsDiscovery.COMMANDER_PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            uri.toString()
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to stage/grant AI-attachment image", e)
            null
        }
    }
}
