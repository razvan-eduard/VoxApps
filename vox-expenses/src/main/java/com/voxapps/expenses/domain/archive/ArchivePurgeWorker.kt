package com.voxapps.expenses.domain.archive

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.voxapps.expenses.ExpensesApplication
import java.util.concurrent.TimeUnit

/**
 * The archive emptying itself, once a day.
 *
 * Always scheduled and cheap when there is nothing to do — with no retention set it reads one
 * setting and stops — for the same reason the spending-limit check is: a schedule that only exists
 * while a setting says so is a second thing that can be out of step with the setting.
 *
 * Nothing here is time-critical. A record whose ninety days elapsed at three in the morning being
 * deleted at four in the afternoon is not a defect, so the work carries no urgency and no
 * constraints beyond the daily period.
 */
class ArchivePurgeWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as ExpensesApplication).container
        val days = container.settingsRepository.getSnapshot().archiveRetentionDays
        val cutoff = ArchiveRetention.cutoff(days, System.currentTimeMillis()) ?: return Result.success()
        container.expensesRepository.purgeArchivedBefore(cutoff)
        return Result.success()
    }
}

object ArchivePurgeScheduler {
    private const val UNIQUE_WORK_NAME = "archive_purge"

    fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<ArchivePurgeWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
