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

    /** [scoped] marks this as the narrow, insert-time candidate-cluster check (as opposed to the
     *  manual/scheduled full-list check) by appending an "INSERT_SCOPED" task-string segment — same
     *  ":"-delimited-segment convention [LlmResultReceiver] already parses for other tasks (e.g.
     *  EXPENSE_PARSE's image-name segment). [LlmResultReceiver] reads this segment back to decide
     *  whether [ExpensesSettings.autoAcceptDuplicateMerges] should auto-apply the result instead of
     *  staging it for review — that setting only ever applies to this scoped path. */
    suspend fun send(context: Context, queue: VoxLlmRequestQueue, expenses: List<ExpenseSummary>, scoped: Boolean = false) {
        if (expenses.size < 2) {
            Logger.w(TAG, "Not sending — fewer than 2 expenses")
            return
        }

        val promptText = ExpenseDeduplicationPromptBuilder.build(expenses)
        val task = if (scoped) "${LlmTasks.EXPENSE_DEDUPLICATION}:INSERT_SCOPED" else LlmTasks.EXPENSE_DEDUPLICATION
        Logger.d(TAG, "Sending ACTION_LLM_PROCESS to $COMMANDER_PACKAGE for ${expenses.size} expenses (scoped=$scoped)")
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
