package com.voxapps.calendarapp.receiver

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
import com.voxapps.ipc.VoxLlmRequest
import com.voxapps.ipc.VoxOcrResult
import com.voxapps.calendarapp.CalendarApplication
import com.voxapps.calendarapp.domain.llm.CalendarScanCleanupPromptBuilder
import com.voxapps.calendarapp.domain.llm.LlmTasks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

private const val TAG = "OcrResultReceiver"
private const val COMMANDER_PACKAGE = "com.voxapps.commander"
private const val CALENDAR_FILE_PROVIDER_AUTHORITY = "com.voxapps.calendar.fileprovider"

/**
 * Calendar's end of Vision's generic OCR hook: receives the raw scanned text back from Vision (the
 * "Scan an event" flow) and forwards it to Commander's generic LLM hook for cleanup — the actual
 * entry gets created when that cleanup reply lands in [LlmResultReceiver] (see its
 * `LlmTasks.CALENDAR_SCAN_CLEANUP` branch, which reuses the same entry-creation path as voice).
 * Guarded by the shared `com.voxapps.vox.permission.OCR_RESULT` signature permission (declared once
 * in `:core:ipc`). Mirrors vox-notes' `OcrResultReceiver`.
 */
class OcrResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != VoxIpc.ACTION_OCR_RESULT) return
        val result = VoxOcrResult.fromJson(intent.getStringExtra(VoxIpc.EXTRA_OCR_PAYLOAD)) ?: return

        if (result.task != LlmTasks.CALENDAR_SCAN_CLEANUP) {
            Logger.d(TAG, "Ignoring unknown OCR task: ${result.task}")
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

                val payload = VoxLlmRequest(
                    sourcePackage = context.packageName,
                    task = LlmTasks.CALENDAR_SCAN_CLEANUP,
                    promptText = CalendarScanCleanupPromptBuilder.build(rawText, existingLayers, language),
                    data = listOf(rawText),
                    attachmentUri = attachmentUri
                ).toJson()
                context.sendBroadcast(
                    Intent(VoxIpc.ACTION_LLM_PROCESS)
                        .setPackage(COMMANDER_PACKAGE)
                        .putExtra(VoxIpc.EXTRA_LLM_PAYLOAD, payload)
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
            val uri = FileProvider.getUriForFile(context, CALENDAR_FILE_PROVIDER_AUTHORITY, targetFile)
            context.grantUriPermission(VoxAppsDiscovery.COMMANDER_PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            uri.toString()
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to stage/grant AI-attachment image", e)
            null
        }
    }
}
