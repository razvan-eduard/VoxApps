package com.voxapps.hub.domain.sync

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedules (or cancels) the periodic [ScheduledSyncWorker] job — mirrors
 * [com.voxapps.hub.domain.backup.BackupScheduler]'s shape. The actual sync cadence lives in
 * [SyncAlarmScheduler]'s wall-clock-aligned alarm chain; this periodic tick exists only to re-arm
 * that chain after reboots and dropped alarms, so its interval is a liveness bound, not a sync
 * cadence.
 */
object ScheduledSyncScheduler {
    private const val UNIQUE_WORK_NAME = "scheduled_peer_sync"
    private const val CHECK_INTERVAL_MINUTES = 15L

    /** Called once at process start (idempotent via [ExistingPeriodicWorkPolicy.KEEP]) — unlike
     *  backups, there's no single settings toggle to react to: the job runs continuously and each
     *  peer's own [PairedPeer.autoSyncEnabled] gates whether it actually does anything each tick. */
    fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<ScheduledSyncWorker>(CHECK_INTERVAL_MINUTES, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
