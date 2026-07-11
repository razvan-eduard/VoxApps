package com.voxapps.notes.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.voxapps.logging.Logger
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxLlmRequest
import com.voxapps.ipc.VoxOcrResult
import com.voxapps.notes.NotesApplication
import com.voxapps.notes.domain.llm.LlmTasks
import com.voxapps.notes.domain.llm.NoteScanCleanupPromptBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TAG = "OcrResultReceiver"
private const val COMMANDER_PACKAGE = "com.voxapps.commander"

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

        Logger.d(TAG, "Scan result received, forwarding to Commander for cleanup")
        val container = (context.applicationContext as NotesApplication).container
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val existingCategories = container.notesRepository.categories.first().map { it.name }
                val language = container.settingsRepository.getSnapshot().language
                val payload = VoxLlmRequest(
                    sourcePackage = context.packageName,
                    task = LlmTasks.NOTE_SCAN_CLEANUP,
                    promptText = NoteScanCleanupPromptBuilder.build(rawText, existingCategories, language),
                    data = listOf(rawText)
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
}
