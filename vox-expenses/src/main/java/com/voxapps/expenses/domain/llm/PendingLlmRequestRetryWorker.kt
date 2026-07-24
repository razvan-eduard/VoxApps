package com.voxapps.expenses.domain.llm

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.voxapps.expenses.ExpensesApplication
import java.util.concurrent.TimeUnit

/**
 * Runs every 15 minutes (see [PendingLlmRequestScheduler]) and re-dispatches any durably-queued LLM
 * request that hasn't had a reply in [STALE_AFTER_MILLIS] — recovers a broadcast Commander silently
 * dropped (stopped/killed at send time, briefly uninstalled) without any user action. [MAX_ATTEMPTS]
 * at this cadence is about 12.5 hours of retrying before a row is left dormant (not deleted, still
 * inspectable) rather than retried forever.
 */
class PendingLlmRequestRetryWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as ExpensesApplication).container
        container.pendingLlmRequestQueue.retryStale(
            context = applicationContext,
            staleAfterMillis = STALE_AFTER_MILLIS,
            maxAttempts = MAX_ATTEMPTS
        )
        return Result.success()
    }

    companion object {
        private val STALE_AFTER_MILLIS = TimeUnit.MINUTES.toMillis(5)
        private const val MAX_ATTEMPTS = 50
    }
}
