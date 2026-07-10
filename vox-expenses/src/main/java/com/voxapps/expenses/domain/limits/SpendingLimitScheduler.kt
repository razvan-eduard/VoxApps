package com.voxapps.expenses.domain.limits

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedules the daily [SpendingLimitCheckWorker] run. Unlike Auto-Merge Categories/expense dedup,
 * there's no user-facing on/off interval setting for this — the worker itself is a cheap no-op when
 * no limits are configured (see its doc comment), so it's simplest to just always keep the schedule
 * running rather than adding a redundant toggle. Requires network for the same reason
 * [com.voxapps.expenses.domain.llm.CategoryAutoMergeScheduler] does — the exchange-rate lookup it may
 * trigger needs one.
 */
object SpendingLimitScheduler {
    private const val UNIQUE_WORK_NAME = "spending_limit_check"

    fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<SpendingLimitCheckWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
