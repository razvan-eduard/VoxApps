package com.voxapps.expenses.domain.llm

import android.content.Context
import android.content.Intent
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxLlmRequest
import com.voxapps.logging.Logger

/**
 * The single reusable "fire the expense-deduplication request" call — used identically by the manual
 * "Find duplicate expenses" button and the scheduled WorkManager job (mirrors vox-notes'
 * NoteDeduplicationRequestSender). Fire-and-forget: Commander replies later via [LlmResultReceiver],
 * which stores the suggestion for review rather than applying it.
 */
object ExpenseDeduplicationRequestSender {

    private const val TAG = "ExpenseDeduplicationRequestSender"
    private const val COMMANDER_PACKAGE = "com.voxapps.commander"

    fun send(context: Context, expenses: List<ExpenseSummary>) {
        if (expenses.size < 2) {
            Logger.w(TAG, "Not sending — fewer than 2 expenses")
            return
        }

        val promptText = ExpenseDeduplicationPromptBuilder.build(expenses)
        val payload = VoxLlmRequest(
            sourcePackage = context.packageName,
            task = LlmTasks.EXPENSE_DEDUPLICATION,
            promptText = promptText,
            data = expenses.map { it.id.toString() }
        ).toJson()

        Logger.d(TAG, "Sending ACTION_LLM_PROCESS to $COMMANDER_PACKAGE for ${expenses.size} expenses")
        context.sendBroadcast(
            Intent(VoxIpc.ACTION_LLM_PROCESS)
                .setPackage(COMMANDER_PACKAGE)
                .putExtra(VoxIpc.EXTRA_LLM_PAYLOAD, payload)
        )
    }
}
