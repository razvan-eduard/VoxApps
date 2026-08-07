package com.voxapps.calendarapp.domain.subscription

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.voxapps.calendarapp.CalendarApplication
import com.voxapps.calendarapp.data.CalendarLayerKind

/** Syncs every currently-subscribed calendar once per periodic tick (see
 *  [CalendarSubscriptionSyncScheduler]) — a no-op when none exist. */
class CalendarSubscriptionSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as CalendarApplication).container
        val subscribed = container.calendarRepository.layersSnapshot().filter { it.kind == CalendarLayerKind.SUBSCRIBED }
        for (layer in subscribed) {
            CalendarSubscriptionSyncEngine.sync(container.calendarRepository, layer, IcsUrlFetcher::fetch)
        }
        return Result.success()
    }
}
