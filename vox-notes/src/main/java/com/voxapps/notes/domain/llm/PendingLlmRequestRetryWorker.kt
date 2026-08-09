package com.voxapps.notes.domain.llm

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.voxapps.notes.NotesApplication

/**
 * Runs every 15 minutes (see [PendingLlmRequestScheduler]) and re-dispatches any durably-queued LLM
 * request that has gone unanswered — recovers a broadcast Commander silently dropped
 * (stopped/killed at send time, briefly uninstalled) without any user action. The staleness
 * window and retry budget are the queue's own policy; see VoxLlmRequestQueue's
 * DEFAULT_STALE_AFTER_MILLIS / DEFAULT_MAX_ATTEMPTS.
 */
class PendingLlmRequestRetryWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as NotesApplication).container
        container.pendingLlmRequestQueue.retryStale(applicationContext)
        return Result.success()
    }
}
