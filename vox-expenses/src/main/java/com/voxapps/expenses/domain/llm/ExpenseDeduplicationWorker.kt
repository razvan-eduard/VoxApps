package com.voxapps.expenses.domain.llm

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.voxapps.expenses.ExpensesApplication
import com.voxapps.ipc.VoxAppsDiscovery
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
        // No one is watching this run to explain a silently-dropped broadcast to (unlike the manual
        // button, which has rememberRequirementGate for that) — just skip it. Not a failure/retry
        // case: nothing about retrying fixes a missing Commander, and the next scheduled run will
        // check again anyway.
        if (!VoxAppsDiscovery.isCommanderInstalled(applicationContext)) return Result.success()
        val container = (applicationContext as ExpensesApplication).container
        val expenses = container.expensesRepository.expenses.first().map {
            ExpenseSummary(it.id, it.title, it.vendor, it.totalAmount, it.currencyCode, it.dateTime)
        }
        ExpenseDeduplicationRequestSender.send(applicationContext, expenses)
        return Result.success()
    }
}
