package com.voxapps.calendarapp.domain.subscription

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Schedules [CalendarSubscriptionSyncWorker] every 12h, requiring a network connection — frequent
 *  enough that a subscribed calendar feels current, well above WorkManager's 15-min floor, and cheap
 *  to always keep scheduled (a no-op tick when no calendar is subscribed yet) — same
 *  always-scheduled-unconditionally convention as [com.voxapps.calendarapp.domain.widget
 *  .WidgetMidnightRefreshScheduler]/[com.voxapps.calendarapp.domain.llm.PendingLlmRequestScheduler]. */
object CalendarSubscriptionSyncScheduler {
    private const val UNIQUE_WORK_NAME = "calendar_subscription_sync"

    fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<CalendarSubscriptionSyncWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
