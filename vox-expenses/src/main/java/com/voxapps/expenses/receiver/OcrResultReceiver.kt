package com.voxapps.expenses.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.voxapps.expenses.ExpensesApplication
import com.voxapps.expenses.domain.llm.ExpenseScanCleanupRequestSender
import com.voxapps.expenses.domain.llm.LlmTasks
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

        Logger.d(TAG, "Scan result received, forwarding to Commander for cleanup")
        val container = (context.applicationContext as ExpensesApplication).container
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Synchronously stage the physical receipt image if provided, plus a sibling
                // .txt file with the raw OCR text — this lets a failed-parse stub expense retry
                // the LLM cleanup later without physically rescanning the paper receipt.
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
                    } catch (e: Exception) {
                        Logger.e(TAG, "Failed to stage receipt image from URI: $uriString", e)
                    }
                }

                ExpenseScanCleanupRequestSender.send(context, container, rawText, storedImageName)
            } finally {
                pending.finish()
            }
        }
    }
}
