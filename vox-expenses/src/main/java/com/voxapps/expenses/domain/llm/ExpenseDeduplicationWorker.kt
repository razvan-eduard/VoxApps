package com.voxapps.expenses.domain.llm

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.voxapps.expenses.ExpensesApplication
import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.expenses.data.toNearDuplicateConfig
import kotlinx.coroutines.flow.first

/**
 * Scheduled counterpart of the manual "Check for duplicates now" button — runs whichever engine(s)
 * [ExpensesSettings.duplicateCheckModeManual] selects, same branching as
 * [com.voxapps.expenses.state.ExpensesStateManager.requestDuplicateCheck] (mirrors vox-notes'
 * NoteDeduplicationWorker in shape, not in the specific 3-way branch, which is expenses-only). The
 * async LLM reply (for the two AI-involving modes) is handled independently by
 * [com.voxapps.expenses.receiver.LlmResultReceiver] whenever it lands, storing the suggestion for
 * review (not applying it), so this worker's job is done as soon as that request is durably enqueued —
 * no need to pre-check whether Commander is installed, [ExpenseDeduplicationRequestSender] routes
 * through [com.voxapps.ipc.VoxLlmRequestQueue] regardless, which self-heals if Commander is installed
 * later. The Local mode branch, by contrast, completes entirely within this one `doWork()` call.
 */
class ExpenseDeduplicationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as ExpensesApplication).container
        val settings = container.settingsRepository.getSnapshot()
        when (settings.duplicateCheckModeManual) {
            ExpensesSettings.MODE_LOCAL -> {
                val groups = container.expensesRepository.findLocalDuplicateGroups(settings.toNearDuplicateConfig())
                container.expenseDeduplicationRepository.mergePendingGroups(groups)
            }
            ExpensesSettings.MODE_LOCAL_AND_AI -> {
                val candidates = container.expensesRepository.duplicateCandidateClusters().flatten().map {
                    ExpenseSummary(it.id, it.title, it.vendor, it.totalAmount, it.currencyCode, it.dateTime)
                }
                if (candidates.isNotEmpty()) {
                    ExpenseDeduplicationRequestSender.send(applicationContext, container.pendingLlmRequestQueue, candidates)
                }
            }
            else -> {
                val expenses = container.expensesRepository.expenses.first().map {
                    ExpenseSummary(it.id, it.title, it.vendor, it.totalAmount, it.currencyCode, it.dateTime)
                }
                ExpenseDeduplicationRequestSender.send(applicationContext, container.pendingLlmRequestQueue, expenses)
            }
        }
        return Result.success()
    }
}
