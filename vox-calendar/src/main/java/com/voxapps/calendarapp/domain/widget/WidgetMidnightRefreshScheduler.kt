package com.voxapps.calendarapp.domain.widget

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * Schedules [WidgetMidnightRefreshWorker] to run once every 24h, with its first run timed to land
 * at the next local midnight — WorkManager then keeps repeating every 24h from that anchor, so
 * later runs stay aligned to midnight (subject to the same battery-optimization jitter every
 * WorkManager periodic job has). [ExistingPeriodicWorkPolicy.KEEP] makes this idempotent across app
 * restarts: only the very first ever call actually sets the midnight-aligned initial delay.
 */
object WidgetMidnightRefreshScheduler {
    private const val UNIQUE_WORK_NAME = "calendar_widget_midnight_refresh"

    fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<WidgetMidnightRefreshWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(millisUntilNextMidnight(), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private fun millisUntilNextMidnight(): Long {
        val now = LocalDateTime.now()
        val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay()
        return Duration.between(now, nextMidnight).toMillis()
    }
}
