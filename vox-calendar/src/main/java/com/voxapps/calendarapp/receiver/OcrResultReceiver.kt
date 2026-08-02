package com.voxapps.calendarapp.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.voxapps.attachments.AttachmentEntity
import com.voxapps.attachments.AttachmentFileStore
import com.voxapps.attachments.AttachmentSource
import com.voxapps.calendarapp.data.CalendarAttachments
import com.voxapps.logging.Logger
import com.voxapps.ipc.VoxAppsDiscovery
import com.voxapps.ipc.VoxCapabilityClient
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxOcrResult
import com.voxapps.calendarapp.CalendarApplication
import com.voxapps.calendarapp.di.CalendarContainer
import com.voxapps.calendarapp.domain.llm.CalendarScanCleanupPromptBuilder
import com.voxapps.calendarapp.domain.llm.CalendarScanRequestSender
import com.voxapps.calendarapp.domain.llm.LlmTasks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

private const val TAG = "OcrResultReceiver"
private const val COMMANDER_PACKAGE = "com.voxapps.commander"
private const val CALENDAR_FILE_PROVIDER_AUTHORITY = "com.voxapps.calendar.fileprovider"

/**
 * Calendar's end of Vision's generic OCR hook — two distinct task families, dispatched by prefix
 * (not exact match, so a colon-suffixed task like an attachment capture's `:$entryId` still routes
 * correctly): [LlmTasks.CALENDAR_SCAN_CLEANUP] (the "Scan an event" flow — forwards the raw scanned
 * text to Commander's generic LLM hook for cleanup; the actual entry gets created when that cleanup
 * reply lands in [LlmResultReceiver]) and [LlmTasks.CALENDAR_ATTACHMENT_CAPTURE] (adding a photo to
 * an entry — always produceOCR=false here, so this just stages the photo(s) and commits
 * AttachmentEntity row(s), no LLM round-trip at all). Guarded by the shared
 * `com.voxapps.vox.permission.OCR_RESULT` signature permission (declared once in `:core:ipc`).
 * Mirrors vox-notes' `OcrResultReceiver`.
 */
class OcrResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != VoxIpc.ACTION_OCR_RESULT) return
        val result = VoxOcrResult.fromJson(intent.getStringExtra(VoxIpc.EXTRA_OCR_PAYLOAD)) ?: return
        val taskParts = result.task.split(":")
        val baseTask = taskParts.getOrNull(0)

        if (baseTask == LlmTasks.CALENDAR_ATTACHMENT_CAPTURE) {
            if (result.status != VoxOcrResult.STATUS_SUCCESS || result.imageUris.isEmpty()) {
                Logger.w(TAG, "Attachment capture succeeded with no image — nothing to stage")
                return
            }
            val container = (context.applicationContext as CalendarApplication).container
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

        if (baseTask != LlmTasks.CALENDAR_SCAN_CLEANUP) {
            Logger.d(TAG, "Ignoring unknown OCR task: ${result.task}")
            return
        }

        // A batch Scan session's single reply (see VoxOcrRequest.CAPTURE_MODE_BATCH) — capture-only,
        // no OCR ran, so there's no text to forward yet. Fire one headless OCR request per photo;
        // each of THOSE replies lands right back in this same branch (now carrying real text) and
        // creates its own independent entry — no other receiver changes needed for batch.
        if (result.status == VoxOcrResult.STATUS_SUCCESS && result.rawText == null && result.imageUris.isNotEmpty()) {
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    result.imageUris.forEach { imageUri ->
                        CalendarScanRequestSender.sendHeadlessBatchPageOcr(context, Uri.parse(imageUri))
                    }
                    Logger.d(TAG, "Batch scan: fired independent headless OCR for ${result.imageUris.size} photo(s)")
                } finally {
                    pending.finish()
                }
            }
            return
        }

        val rawText = result.rawText
        if (result.status != VoxOcrResult.STATUS_SUCCESS || rawText.isNullOrBlank()) {
            Logger.w(TAG, "Scan failed or empty: ${result.error}")
            // Unconditional (not gated behind a save-toast setting) — a failure toast is the only
            // signal the user has that the scan didn't work, unlike a success which is also visible
            // as a new entry.
            val languageManager = (context.applicationContext as CalendarApplication).container.languageManager
            Toast.makeText(context, languageManager.getString("scan_save_failed"), Toast.LENGTH_SHORT).show()
            return
        }

        val container = (context.applicationContext as CalendarApplication).container
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
                val existingLayers = container.calendarRepository.layers.first().map { it.name }
                val settings = container.settingsRepository.getSnapshot()
                val language = settings.language

                // No image-attached record equivalent here (unlike Expenses) and no retry mechanism,
                // so the AI-attachment copy only ever needs to live long enough for this one call —
                // staged into cache, not persisted. Gated on our own attachPhotoOnScan toggle
                // (checked here) and Vision having actually provided a downscaled copy in the first
                // place (its own "send photo to AI" setting); the multimodal-engine check happens
                // inside the grant.
                val attachmentUri = if (settings.attachPhotoOnScan) {
                    result.aiImageUri?.let { aiUriString -> stageAndGrantAiCopy(context, aiUriString) }
                } else null

                container.pendingLlmRequestQueue.enqueueAndSend(
                    context = context,
                    sourcePackage = context.packageName,
                    task = LlmTasks.CALENDAR_SCAN_CLEANUP,
                    promptText = CalendarScanCleanupPromptBuilder.build(rawText, existingLayers, language),
                    targetPackage = COMMANDER_PACKAGE,
                    data = listOf(rawText),
                    attachmentUri = attachmentUri
                )
            } finally {
                pending.finish()
            }
        }
    }

    /** Stages every returned photo and commits it as a real AttachmentEntity row — no OCR text
     *  involved at all, Calendar never requests it for attachments (see [LlmTasks.
     *  CALENDAR_ATTACHMENT_CAPTURE]'s doc comment). A 2+ photo reply (a stitch group — see
     *  [com.voxapps.ipc.VoxOcrRequest.CAPTURE_MODE_STITCH]) shares one groupId and is marked
     *  [AttachmentSource.STITCHED] so the zoom view only offers whole-group delete for it; a batch
     *  reply's photos are independent (ungrouped, [AttachmentSource.MANUAL]) — mirrors vox-expenses'
     *  OcrResultReceiver.handleAttachmentCapture. Task string shape:
     *  "$CALENDAR_ATTACHMENT_CAPTURE:$entryId". */
    private suspend fun handleAttachmentCapture(context: Context, container: CalendarContainer, taskParts: List<String>, imageUris: List<String>) {
        val entryId = taskParts.getOrNull(1)?.toLongOrNull()
        if (entryId == null) {
            Logger.w(TAG, "Attachment capture missing entryId: ${taskParts.joinToString(":")}")
            return
        }
        val stagedFileNames = imageUris.mapNotNull { AttachmentFileStore.stage(context, Uri.parse(it), CalendarAttachments.DIR) }
        if (stagedFileNames.isEmpty()) {
            Logger.e(TAG, "Failed to stage any attachment capture image")
            return
        }
        val groupId = if (stagedFileNames.size > 1) UUID.randomUUID().toString() else null
        val source = if (groupId != null) AttachmentSource.STITCHED else AttachmentSource.MANUAL
        stagedFileNames.forEachIndexed { index, fileName ->
            container.attachmentDao.insert(
                AttachmentEntity(
                    recordType = CalendarAttachments.RECORD_TYPE,
                    recordId = entryId,
                    fileName = fileName,
                    source = source,
                    createdAt = System.currentTimeMillis(),
                    groupId = groupId,
                    groupOrder = index
                )
            )
        }
        Logger.d(TAG, "Attachment capture staged ${stagedFileNames.size} photo(s) for entry $entryId (group=$groupId)")
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
            val uri = FileProvider.getUriForFile(context, CALENDAR_FILE_PROVIDER_AUTHORITY, targetFile)
            context.grantUriPermission(VoxAppsDiscovery.COMMANDER_PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            uri.toString()
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to stage/grant AI-attachment image", e)
            null
        }
    }
}
