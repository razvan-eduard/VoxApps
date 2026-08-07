package com.voxapps.expenses.domain.llm

import android.content.Context
import com.voxapps.ipc.VoxAppsDiscovery.COMMANDER_PACKAGE
import com.voxapps.ipc.VoxLlmRequestQueue
import com.voxapps.logging.Logger


/**
 * The single reusable "fire the expense-deduplication request" call — used identically by the manual
 * "Find duplicate expenses" button and the scheduled WorkManager job (mirrors vox-notes'
 * NoteDeduplicationRequestSender). Routed through [VoxLlmRequestQueue] for durable, retryable
 * delivery; Commander replies later via [LlmResultReceiver], which stores the suggestion for review
 * rather than applying it.
 */
object ExpenseDeduplicationRequestSender {

    private const val TAG = "ExpenseDeduplicationRequestSender"

    /** [scoped] marks this as the narrow, insert-time candidate-cluster check (as opposed to the
     *  manual/scheduled full-list check) by appending an "INSERT_SCOPED" task-string segment.
     *  [autoApply] indicates the result should be applied immediately without review. */
    suspend fun send(
        context: Context,
        queue: VoxLlmRequestQueue,
        expenses: List<ExpenseSummary>,
        scoped: Boolean = false,
        autoApply: Boolean = false
    ) {
        if (expenses.size < 2) {
            Logger.w(TAG, "Not sending — fewer than 2 expenses")
            return
        }

        val promptText = ExpenseDeduplicationPromptBuilder.build(expenses)
        var task = LlmTasks.EXPENSE_DEDUPLICATION
        if (scoped) task += ":INSERT_SCOPED"
        if (autoApply) task += ":BATCH_AUTO_APPLY"

        Logger.d(TAG, "Sending ACTION_LLM_PROCESS to $COMMANDER_PACKAGE for ${expenses.size} expenses (scoped=$scoped, autoApply=$autoApply)")
        queue.enqueueAndSend(
            context = context,
            sourcePackage = context.packageName,
            task = task,
            promptText = promptText,
            targetPackage = COMMANDER_PACKAGE,
            data = expenses.map { it.id.toString() }
        )
    }
}
