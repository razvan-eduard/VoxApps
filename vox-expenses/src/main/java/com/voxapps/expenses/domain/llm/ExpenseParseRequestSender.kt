package com.voxapps.expenses.domain.llm

import android.content.Context
import android.content.Intent
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxLlmRequest
import com.voxapps.logging.Logger

private const val TAG = "ExpenseParseRequestSender"
private const val COMMANDER_PACKAGE = "com.voxapps.commander"

/**
 * Fires the generic-LLM-hook request that turns a raw spoken utterance into structured expense
 * fields (see [ExpenseParsePromptBuilder]). Mirrors vox-notes' CategoryMergeRequestSender/
 * ScanRequestSender in shape — fire-and-forget; the async reply arrives later via
 * [com.voxapps.expenses.receiver.LlmResultReceiver].
 */
object ExpenseParseRequestSender {
    fun send(context: Context, rawText: String, existingCategories: List<String>, defaultCurrency: String, languageCode: String) {
        val promptText = ExpenseParsePromptBuilder.build(rawText, existingCategories, defaultCurrency, languageCode)
        val payload = VoxLlmRequest(
            sourcePackage = context.packageName,
            task = LlmTasks.EXPENSE_PARSE,
            promptText = promptText,
            data = listOf(rawText)
        ).toJson()

        Logger.d(TAG, "Sending ACTION_LLM_PROCESS to $COMMANDER_PACKAGE for voice-expense parsing")
        context.sendBroadcast(
            Intent(VoxIpc.ACTION_LLM_PROCESS)
                .setPackage(COMMANDER_PACKAGE)
                .putExtra(VoxIpc.EXTRA_LLM_PAYLOAD, payload)
        )
    }
}
