package com.voxapps.notes.domain.llm

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.voxapps.notes.data.preferences.NotesSettings
import java.util.concurrent.TimeUnit

/**
 * Schedules (or cancels) the periodic [CategoryAutoMergeWorker] job to match the user's
 * `scheduledMergeInterval` setting. Requires a network connection — cloud LLM engines need it, and
 * WorkManager can't introspect which engine is currently selected at schedule time, so this errs
 * battery-friendly rather than trying to run local-only.
 */
object CategoryAutoMergeScheduler {
    private const val UNIQUE_WORK_NAME = "category_auto_merge"

    fun reschedule(context: Context, interval: String) {
        val workManager = WorkManager.getInstance(context)
        val periodMillis = when (interval) {
            NotesSettings.INTERVAL_DAILY -> TimeUnit.DAYS.toMillis(1)
            NotesSettings.INTERVAL_WEEKLY -> TimeUnit.DAYS.toMillis(7)
            NotesSettings.INTERVAL_MONTHLY -> TimeUnit.DAYS.toMillis(30)
            else -> null
        }
        if (periodMillis == null) {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<CategoryAutoMergeWorker>(periodMillis, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager.enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
