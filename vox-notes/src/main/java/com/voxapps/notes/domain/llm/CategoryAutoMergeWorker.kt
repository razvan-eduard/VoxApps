package com.voxapps.notes.domain.llm

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.voxapps.ipc.VoxAppsDiscovery
import com.voxapps.notes.NotesApplication
import kotlinx.coroutines.flow.first

/**
 * Scheduled counterpart of the manual "Auto-Merge Categories" button — gathers the current category
 * list + language and fires the exact same [CategoryMergeRequestSender] call. The async LLM reply
 * (and the actual merge) is handled independently by [com.voxapps.notes.receiver.LlmResultReceiver]
 * whenever it lands, so this worker's job is done as soon as the request is sent.
 */
class CategoryAutoMergeWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // No one is watching this run to explain a silently-dropped broadcast to (unlike the manual
        // button, which has rememberRequirementGate for that) — just skip it. Not a failure/retry
        // case: nothing about retrying fixes a missing Commander, and the next scheduled run will
        // check again anyway.
        if (!VoxAppsDiscovery.isCommanderInstalled(applicationContext)) return Result.success()
        val container = (applicationContext as NotesApplication).container
        val categoryNames = container.notesRepository.categories.first().map { it.name }
        val language = container.settingsRepository.getSnapshot().language
        CategoryMergeRequestSender.send(applicationContext, categoryNames, language)
        return Result.success()
    }
}
