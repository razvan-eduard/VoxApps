package com.voxapps.expenses.domain.llm

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.voxapps.expenses.ExpensesApplication
import kotlinx.coroutines.flow.first

/**
 * Scheduled counterpart of the manual "Find duplicate expenses" button — gathers the current expenses
 * and fires the exact same [ExpenseDeduplicationRequestSender] call (mirrors vox-notes'
 * NoteDeduplicationWorker). The async LLM reply is handled independently by
 * [com.voxapps.expenses.receiver.LlmResultReceiver] whenever it lands, storing the suggestion for
 * review (not applying it), so this worker's job is done as soon as the request is sent.
 */
class ExpenseDeduplicationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as ExpensesApplication).container
        val expenses = container.expensesRepository.expenses.first().map {
            ExpenseSummary(it.id, it.title, it.vendor, it.totalAmount, it.currencyCode, it.dateTime)
        }
        ExpenseDeduplicationRequestSender.send(applicationContext, expenses)
        return Result.success()
    }
}
