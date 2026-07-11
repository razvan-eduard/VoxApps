package com.voxapps.expenses.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.voxapps.expenses.ExpensesApplication
import com.voxapps.expenses.domain.llm.ExpenseScanCleanupPromptBuilder
import com.voxapps.expenses.domain.llm.LlmTasks
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxLlmRequest
import com.voxapps.ipc.VoxOcrResult
import com.voxapps.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TAG = "OcrResultReceiver"
private const val COMMANDER_PACKAGE = "com.voxapps.commander"

/**
 * Expenses' end of Vision's generic OCR hook: receives the raw scanned text back from Vision (the
 * "Scan receipt" flow) and forwards it to Commander's generic LLM hook for cleanup — the actual
 * expense gets created when that cleanup reply lands in [LlmResultReceiver] (its
 * `LlmTasks.EXPENSE_SCAN_CLEANUP` branch). Mirrors vox-notes' OcrResultReceiver. Guarded by the shared
 * `com.voxapps.vox.permission.OCR_RESULT` signature permission (declared once in `:core:ipc`'s
 * manifest) — also what makes this receiver discoverable by Vision's dynamic dispatcher (see the
 * `com.voxapps.vox.ocr.task` meta-data on this receiver in the manifest).
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
            // Unconditional (not gated behind voiceSaveToastEnabled, which is opt-in and off by
            // default) — a failure toast is the only signal the user has that the scan didn't work,
            // unlike a success which is also visible as a new list item.
            val languageManager = (context.applicationContext as ExpensesApplication).container.languageManager
            Toast.makeText(context, languageManager.getString("scan_save_failed"), Toast.LENGTH_SHORT).show()
            return
        }

        Logger.d(TAG, "Scan result received, forwarding to Commander for cleanup")
        val container = (context.applicationContext as ExpensesApplication).container
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val existingCategories = container.expensesRepository.categories.first().map { it.name }
                val settings = container.settingsRepository.getSnapshot()
                val payload = VoxLlmRequest(
                    sourcePackage = context.packageName,
                    task = LlmTasks.EXPENSE_SCAN_CLEANUP,
                    promptText = ExpenseScanCleanupPromptBuilder.build(rawText, existingCategories, settings.defaultCurrency, settings.language),
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
