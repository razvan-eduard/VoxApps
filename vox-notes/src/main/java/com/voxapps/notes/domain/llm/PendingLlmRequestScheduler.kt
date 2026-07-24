package com.voxapps.notes.domain.llm

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedules the recurring [PendingLlmRequestRetryWorker] run. No user-facing on/off setting, same
 * reasoning as vox-expenses' equivalent: the worker is a cheap no-op when the queue is empty, so
 * it's simplest to just always keep it scheduled. 15 minutes is WorkManager's minimum periodic
 * interval — the shortest cadence available for recovering a request whose broadcast to Commander
 * never got a reply.
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
