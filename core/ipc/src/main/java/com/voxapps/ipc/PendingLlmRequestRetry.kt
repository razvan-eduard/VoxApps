package com.voxapps.ipc

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * How the retry machinery reaches an app's queue: the Application implements this. The worker
 * runs inside whichever app scheduled it, so the cast is to the one object every satellite
 * already has — its own Application — rather than to any app-specific class this module would
 * otherwise have to know. This pair used to exist byte-identically in three satellites; the
 * entity, DAO and queue it serves always lived here, so the stragglers moved in with them.
 */
interface VoxLlmQueueHost {
    val voxLlmRequestQueue: VoxLlmRequestQueue
}

/**
 * Runs every 15 minutes (see [PendingLlmRequestScheduler]) and re-dispatches any durably-queued
 * LLM request that has gone unanswered — recovers a broadcast Commander silently dropped
 * (stopped/killed at send time, briefly uninstalled) without any user action. The staleness
 * window and retry budget are the queue's own policy; see [VoxLlmRequestQueue].
 */
class PendingLlmRequestRetryWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        (applicationContext as? VoxLlmQueueHost)
            ?.voxLlmRequestQueue
            ?.retryStale(applicationContext)
        return Result.success()
    }
}

/**
 * Schedules the recurring [PendingLlmRequestRetryWorker] run. No user-facing on/off setting: the
 * worker is a cheap no-op when the queue is empty, so it is simplest to always keep it
 * scheduled. 15 minutes is WorkManager's minimum periodic interval — the shortest cadence
 * available for recovering a request whose broadcast to Commander never got a reply.
 */
object PendingLlmRequestScheduler {
    private const val UNIQUE_WORK_NAME = "pending_llm_request_retry"

    fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<PendingLlmRequestRetryWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
