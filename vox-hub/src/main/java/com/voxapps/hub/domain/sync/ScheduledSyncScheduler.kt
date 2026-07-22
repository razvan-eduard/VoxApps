package com.voxapps.hub.domain.sync

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedules (or cancels) the periodic [ScheduledSyncWorker] job — mirrors
 * [com.voxapps.hub.domain.backup.BackupScheduler]'s shape. Runs at WorkManager's own periodic floor
 * (15 minutes) regardless of any individual peer's [PairedPeer.autoSyncIntervalMinutes]; the worker
 * itself decides per peer whether enough time has actually elapsed since
 * [PairedPeer.lastAttemptedSyncAt] to attempt a connection this tick — see that field's doc comment
 * for why a fixed, frequent check tick is the right way to honor a per-peer interval that can't be
 * scheduled any more precisely than WorkManager's own floor allows.
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
