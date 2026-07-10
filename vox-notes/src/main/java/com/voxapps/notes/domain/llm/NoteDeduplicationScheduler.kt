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
 * Schedules (or cancels) the periodic [NoteDeduplicationWorker] job to match the user's
 * `scheduledNoteDedupInterval` setting. Requires a network connection, same reasoning as
 * [CategoryAutoMergeScheduler].
 */
object NoteDeduplicationScheduler {
    private const val UNIQUE_WORK_NAME = "note_deduplication"

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
        val request = PeriodicWorkRequestBuilder<NoteDeduplicationWorker>(periodMillis, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager.enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
