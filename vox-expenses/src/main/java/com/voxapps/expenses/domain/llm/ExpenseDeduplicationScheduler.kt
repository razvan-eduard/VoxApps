package com.voxapps.expenses.domain.llm

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.voxapps.expenses.data.preferences.ExpensesSettings
import java.util.concurrent.TimeUnit

/**
 * Schedules (or cancels) the periodic [ExpenseDeduplicationWorker] job to match the user's
 * `scheduledExpenseDedupInterval` setting (mirrors vox-notes' NoteDeduplicationScheduler). Requires a
 * network connection, same reasoning as [CategoryAutoMergeScheduler].
 */
object ExpenseDeduplicationScheduler {
    private const val UNIQUE_WORK_NAME = "expense_deduplication"

    fun reschedule(context: Context, interval: String) {
        val workManager = WorkManager.getInstance(context)
        val periodMillis = when (interval) {
            ExpensesSettings.INTERVAL_HOURLY -> TimeUnit.HOURS.toMillis(1)
            ExpensesSettings.INTERVAL_DAILY -> TimeUnit.DAYS.toMillis(1)
            ExpensesSettings.INTERVAL_WEEKLY -> TimeUnit.DAYS.toMillis(7)
            ExpensesSettings.INTERVAL_MONTHLY -> TimeUnit.DAYS.toMillis(30)
            else -> null
        }
        if (periodMillis == null) {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<ExpenseDeduplicationWorker>(periodMillis, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager.enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
