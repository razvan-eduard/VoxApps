package com.voxapps.expenses.domain.llm

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.voxapps.expenses.ExpensesApplication
import kotlinx.coroutines.flow.first

/**
 * Scheduled counterpart of the manual "Auto-Merge Categories" button — gathers the current category
 * list + language and fires the exact same [CategoryMergeRequestSender] call (mirrors vox-notes'
 * CategoryAutoMergeWorker). The async LLM reply is handled independently by
 * [com.voxapps.expenses.receiver.LlmResultReceiver] whenever it lands, storing the suggestion for
 * review (not applying it), so this worker's job is done as soon as the request is sent.
 */
class CategoryAutoMergeWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as ExpensesApplication).container
        val categoryNames = container.expensesRepository.categories.first().map { it.name }
        val language = container.settingsRepository.getSnapshot().language
        CategoryMergeRequestSender.send(applicationContext, categoryNames, language)
        return Result.success()
    }
}
