package com.voxapps.expenses.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.voxapps.expenses.ExpensesApplication
import com.voxapps.expenses.domain.llm.DateTimeRegexParser
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
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

private const val TAG = "OcrResultReceiver"
private const val COMMANDER_PACKAGE = "com.voxapps.commander"

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

        Logger.d(TAG, "Scan result received, forwarding to Commander for cleanup")
        val container = (context.applicationContext as ExpensesApplication).container
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Synchronously stage the physical receipt image if provided.
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
                        storedImageName = fileName
                    } catch (e: Exception) {
                        Logger.e(TAG, "Failed to stage receipt image from URI: $uriString", e)
                    }
                }

                val existingCategories = container.expensesRepository.categories.first().map { it.name }
                val settings = container.settingsRepository.getSnapshot()

                // Optimization: Pre-parse date/time via Regex before sending to LLM
                val preParsed = DateTimeRegexParser.parse(rawText)
                android.util.Log.println(android.util.Log.ASSERT, TAG, "[DEBUG] Regex Pass - Date: ${preParsed.date}, Time: ${preParsed.time} | Raw: ${rawText.take(50)}")
                
                // Embed stored image filename in task ID metadata (format "TASK:IMAGE_NAME")
                val taskWithMeta = if (storedImageName != null) {
                    "${LlmTasks.EXPENSE_SCAN_CLEANUP}:$storedImageName"
                } else {
                    LlmTasks.EXPENSE_SCAN_CLEANUP
                }

                val payload = VoxLlmRequest(
                    sourcePackage = context.packageName,
                    task = taskWithMeta,
                    promptText = ExpenseScanCleanupPromptBuilder.build(
                        rawText, 
                        existingCategories, 
                        settings.defaultCurrency, 
                        settings.language,
                        preParsedDate = preParsed.date,
                        preParsedTime = preParsed.time
                    ),
                    data = emptyList()
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
