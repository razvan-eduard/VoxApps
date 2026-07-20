package com.voxapps.expenses.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.voxapps.expenses.ExpensesApplication
import com.voxapps.expenses.domain.llm.ExpenseScanCleanupRequestSender
import com.voxapps.expenses.domain.llm.LlmTasks
import com.voxapps.expenses.domain.llm.MultimodalAttachmentResolver
import com.voxapps.ipc.VoxAppsDiscovery
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxOcrResult
import com.voxapps.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

private const val TAG = "OcrResultReceiver"

/**
 * Handles incoming raw OCR results from Vision. In the "Zero-Loss" receipt flow, this receiver
 * synchronously copies the shared receipt image from Vision's FileProvider into Expenses' own
 * internal storage before forwarding the OCR text to Commander.
 */
class OcrResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != VoxIpc.ACTION_OCR_RESULT) return
        val result = VoxOcrResult.fromJson(intent.getStringExtra(VoxIpc.EXTRA_OCR_PAYLOAD)) ?: return

        if (result.task != LlmTasks.EXPENSE_SCAN_CLEANUP) {
            Logger.d(TAG, "Ignoring unknown OCR task: ${result.task}")
            return
        }

        val rawText = result.rawText
        if (result.status != VoxOcrResult.STATUS_SUCCESS || rawText.isNullOrBlank()) {
            Logger.w(TAG, "Scan failed or empty: ${result.error}")
            val languageManager = (context.applicationContext as ExpensesApplication).container.languageManager
            Toast.makeText(context, languageManager.getString("scan_save_failed"), Toast.LENGTH_SHORT).show()
            return
        }

        val container = (context.applicationContext as ExpensesApplication).container
        // Rare edge case (the Scan entry point itself already checks this before ever launching
        // Vision) — Commander could still get uninstalled mid-scan. Nothing downstream can do
        // anything without it, so skip straight to telling the user why instead of staging a photo
        // that would never actually become an expense.
        if (!VoxAppsDiscovery.isCommanderInstalled(context)) {
            Toast.makeText(context, container.languageManager.getString("commander_required_message"), Toast.LENGTH_SHORT).show()
            return
        }

        Logger.d(TAG, "Scan result received, forwarding to Commander for cleanup")
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Synchronously stage the physical receipt image if provided, plus a sibling
                // .txt file with the raw OCR text — this lets a failed-parse stub expense retry
                // the LLM cleanup later without physically rescanning the paper receipt. Also stages
                // Vision's separate, already-downscaled AI-attachment copy (result.aiImageUri) as a
                // second sibling file whenever Vision's own "send photo to AI" setting provided one —
                // staged unconditionally (regardless of our own attachPhotoOnScan/attachPhotoOnRetry
                // toggles) so a later retry can still use it even if the retry toggle gets turned on
                // after this scan.
                var storedImageName: String? = null
                result.imageUri?.let { uriString ->
                    try {
                        val uri = Uri.parse(uriString)
                        val fileName = "rec_${UUID.randomUUID()}.jpg"
                        val receiptsDir = File(context.filesDir, "receipts").apply { mkdirs() }
                        val targetFile = File(receiptsDir, fileName)

                        context.contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(targetFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        File(receiptsDir, fileName.substringBeforeLast('.') + ".txt").writeText(rawText)
                        storedImageName = fileName

                        result.aiImageUri?.let { aiUriString ->
                            try {
                                val aiUri = Uri.parse(aiUriString)
                                val aiTargetFile = File(receiptsDir, MultimodalAttachmentResolver.aiCopyFileName(fileName))
                                context.contentResolver.openInputStream(aiUri)?.use { input ->
                                    FileOutputStream(aiTargetFile).use { output -> input.copyTo(output) }
                                }
                            } catch (e: Exception) {
                                Logger.e(TAG, "Failed to stage AI-attachment image from URI: $aiUriString", e)
                            }
                        }
                    } catch (e: Exception) {
                        Logger.e(TAG, "Failed to stage receipt image from URI: $uriString", e)
                    }
                }

                // OCR text is always sent regardless (see the collapsed voice-command plan: skipping
                // OCR traded away an unvalidated accuracy assumption for a real cost, so it stays as
                // the deterministic prior). The photo is an *additional* attachment, gated on this
                // app's own attachPhotoOnScan toggle (checked here) as well as Commander's engine
                // actually being multimodal (checked inside resolve()).
                val settings = container.settingsRepository.getSnapshot()
                val attachmentUri = MultimodalAttachmentResolver.resolve(context, storedImageName, settings.attachPhotoOnScan)

                ExpenseScanCleanupRequestSender.send(context, container, rawText, storedImageName, attachmentUri = attachmentUri)
            } finally {
                pending.finish()
            }
        }
    }
}
