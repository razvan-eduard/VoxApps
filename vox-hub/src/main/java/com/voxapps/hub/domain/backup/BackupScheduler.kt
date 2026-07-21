package com.voxapps.hub.domain.backup

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.voxapps.hub.data.preferences.HubSettings
import java.util.concurrent.TimeUnit

/**
 * Schedules (or cancels) the periodic [BackupWorker] job to match the user's `backupInterval`
 * setting. No network constraint — unlike the satellites' LLM-backed scheduled jobs, a backup is
 * purely local IPC + zip-writing.
 */
object BackupScheduler {
    private const val UNIQUE_WORK_NAME = "scheduled_backup"

    fun reschedule(context: Context, interval: String) {
        val workManager = WorkManager.getInstance(context)
        val periodMillis = when (interval) {
            HubSettings.INTERVAL_DAILY -> TimeUnit.DAYS.toMillis(1)
            HubSettings.INTERVAL_WEEKLY -> TimeUnit.DAYS.toMillis(7)
            HubSettings.INTERVAL_MONTHLY -> TimeUnit.DAYS.toMillis(30)
            else -> null
        }
        if (periodMillis == null) {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<BackupWorker>(periodMillis, TimeUnit.MILLISECONDS).build()
        workManager.enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
