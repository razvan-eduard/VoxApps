package com.voxapps.expenses.domain.llm

import android.content.Context
import com.voxapps.ipc.VoxAppsDiscovery.COMMANDER_PACKAGE
import com.voxapps.ipc.VoxLlmRequestQueue
import com.voxapps.logging.Logger

private const val TAG = "ExpenseParseRequestSender"

/**
 * Fires the generic-LLM-hook request that turns a raw spoken utterance into structured expense
 * fields (see [ExpenseParsePromptBuilder]). Mirrors vox-notes' CategoryMergeRequestSender/
 * ScanRequestSender in shape. Routed through [VoxLlmRequestQueue] for durable, retryable delivery;
 * the async reply arrives later via [com.voxapps.expenses.receiver.LlmResultReceiver].
 */
object ExpenseParseRequestSender {
    suspend fun send(
        context: Context,
        queue: VoxLlmRequestQueue,
        rawText: String,
        existingCategories: List<String>,
        defaultCurrency: String,
        languageCode: String
    ) {
        val promptText = ExpenseParsePromptBuilder.build(rawText, existingCategories, defaultCurrency, languageCode)
        Logger.d(TAG, "Sending ACTION_LLM_PROCESS to $COMMANDER_PACKAGE for voice-expense parsing")
        queue.enqueueAndSend(
            context = context,
            sourcePackage = context.packageName,
            task = LlmTasks.EXPENSE_PARSE,
            promptText = promptText,
            targetPackage = COMMANDER_PACKAGE,
            data = listOf(rawText)
        )
    }
}
