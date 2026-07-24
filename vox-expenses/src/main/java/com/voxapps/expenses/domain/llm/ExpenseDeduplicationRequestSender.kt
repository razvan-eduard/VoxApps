package com.voxapps.expenses.domain.llm

import android.content.Context
import com.voxapps.ipc.VoxLlmRequestQueue
import com.voxapps.logging.Logger

private const val COMMANDER_PACKAGE = "com.voxapps.commander"

/**
 * The single reusable "fire the expense-deduplication request" call — used identically by the manual
 * "Find duplicate expenses" button and the scheduled WorkManager job (mirrors vox-notes'
 * NoteDeduplicationRequestSender). Routed through [VoxLlmRequestQueue] for durable, retryable
 * delivery; Commander replies later via [LlmResultReceiver], which stores the suggestion for review
 * rather than applying it.
 */
object ExpenseDeduplicationRequestSender {

    private const val TAG = "ExpenseDeduplicationRequestSender"

    suspend fun send(context: Context, queue: VoxLlmRequestQueue, expenses: List<ExpenseSummary>) {
        if (expenses.size < 2) {
            Logger.w(TAG, "Not sending — fewer than 2 expenses")
            return
        }

        val promptText = ExpenseDeduplicationPromptBuilder.build(expenses)
        Logger.d(TAG, "Sending ACTION_LLM_PROCESS to $COMMANDER_PACKAGE for ${expenses.size} expenses")
        queue.enqueueAndSend(
            context = context,
            sourcePackage = context.packageName,
            task = LlmTasks.EXPENSE_DEDUPLICATION,
            promptText = promptText,
            targetPackage = COMMANDER_PACKAGE,
            data = expenses.map { it.id.toString() }
        )
    }
}
