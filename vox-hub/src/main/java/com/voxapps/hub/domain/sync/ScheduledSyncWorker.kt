package com.voxapps.hub.domain.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * The safety net under [SyncAlarmScheduler]'s self-re-arming alarm chain: alarms die on reboot, and
 * a Doze-deferred firing can be dropped entirely, so this periodic tick re-arms the chain whenever
 * it finds auto-sync peers configured. It runs no sync of its own — a phase-blind attempt from an
 * independently-drifting periodic job is exactly the rendezvous failure the aligned slots exist to
 * replace.
 */
class ScheduledSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        SyncAlarmScheduler.ensureScheduled(applicationContext)
        return Result.success()
    }
}
