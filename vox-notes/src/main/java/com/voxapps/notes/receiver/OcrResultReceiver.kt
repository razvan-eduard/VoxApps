package com.voxapps.notes.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.voxapps.logging.Logger
import com.voxapps.ipc.VoxAppsDiscovery
import com.voxapps.ipc.VoxCapabilityClient
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxOcrResult
import com.voxapps.notes.NotesApplication
import com.voxapps.notes.domain.llm.LlmTasks
import com.voxapps.notes.domain.llm.NoteScanCleanupPromptBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

private const val TAG = "OcrResultReceiver"
private const val COMMANDER_PACKAGE = "com.voxapps.commander"
private const val NOTES_FILE_PROVIDER_AUTHORITY = "com.voxapps.notes.fileprovider"

/**
 * Notes' end of Vision's generic OCR hook: receives the raw scanned text back from Vision (the
 * "Scanează o notiță" flow) and forwards it to Commander's generic LLM hook for cleanup — the actual
 * note gets created when that cleanup reply lands in [LlmResultReceiver] (see its
 * `LlmTasks.NOTE_SCAN_CLEANUP` branch). Guarded by the shared
 * `com.voxapps.vox.permission.OCR_RESULT` signature permission (declared once in `:core:ipc`).
 */
class OcrResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != VoxIpc.ACTION_OCR_RESULT) return
        val result = VoxOcrResult.fromJson(intent.getStringExtra(VoxIpc.EXTRA_OCR_PAYLOAD)) ?: return

        if (result.task != LlmTasks.NOTE_SCAN_CLEANUP) {
            Logger.d(TAG, "Ignoring unknown OCR task: ${result.task}")
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
                val existingCategories = container.notesRepository.categories.first().map { it.name }
                val settings = container.settingsRepository.getSnapshot()
                val language = settings.language

                // No receipt-record equivalent here (unlike Expenses) and no retry mechanism, so the
                // AI-attachment copy only ever needs to live long enough for this one call — staged
                // into cache, not persisted. Gated on our own attachPhotoOnScan toggle (checked here)
                // and Vision having actually provided a downscaled copy in the first place (its own
                // "send photo to AI" setting); the multimodal-engine check happens inside the grant.
                val attachmentUri = if (settings.attachPhotoOnScan) {
                    result.aiImageUri?.let { aiUriString -> stageAndGrantAiCopy(context, aiUriString) }
                } else null

                container.pendingLlmRequestQueue.enqueueAndSend(
                    context = context,
                    sourcePackage = context.packageName,
                    task = LlmTasks.NOTE_SCAN_CLEANUP,
                    promptText = NoteScanCleanupPromptBuilder.build(rawText, existingCategories, language),
                    targetPackage = COMMANDER_PACKAGE,
                    data = listOf(rawText),
                    attachmentUri = attachmentUri
                )
            } finally {
                pending.finish()
            }
        }
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
