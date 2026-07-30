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

    /** Maps a `scheduledExpenseDedupInterval` setting value to its WorkManager period, or `null`
     *  for "off" (any unrecognized value, e.g. [ExpensesSettings.INTERVAL_OFF]) — pulled out as a
     *  pure function so it's unit-testable without a real WorkManager/Context. */
    internal fun periodMillisFor(interval: String): Long? = when (interval) {
        ExpensesSettings.INTERVAL_HOURLY -> TimeUnit.HOURS.toMillis(1)
        ExpensesSettings.INTERVAL_DAILY -> TimeUnit.DAYS.toMillis(1)
        ExpensesSettings.INTERVAL_WEEKLY -> TimeUnit.DAYS.toMillis(7)
        ExpensesSettings.INTERVAL_MONTHLY -> TimeUnit.DAYS.toMillis(30)
        else -> null
    }

    fun reschedule(context: Context, interval: String) {
        val workManager = WorkManager.getInstance(context)
        val periodMillis = periodMillisFor(interval)
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
