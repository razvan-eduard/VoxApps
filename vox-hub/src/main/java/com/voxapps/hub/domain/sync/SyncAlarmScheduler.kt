package com.voxapps.hub.domain.sync

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.voxapps.hub.HubApplication
import com.voxapps.logging.Logger
import java.util.concurrent.TimeUnit

/**
 * The rendezvous problem: an auto-sync only happens when BOTH phones try within the server's listen
 * window, and two independently-phased periodic jobs essentially never overlap. The fix is a shared
 * clock instead of shared luck — both phones fire at the same wall-clock slot boundaries (epoch
 * multiples of the peer's interval, timezone-independent), the server listens through most of the
 * slot window, and the client retries into it. Alarms come from [AlarmManager.setWindow]: inexact
 * within the window is fine (the window is why there IS a window), and no exact-alarm permission is
 * involved.
 *
 * The alarm chain re-arms itself after every firing; [ScheduledSyncWorker]'s periodic tick is the
 * safety net that re-arms it after reboots or a Doze-dropped firing.
 */
object SyncAlarmScheduler {
    private const val REQUEST_CODE = 41

    /** How far past a slot boundary a firing still counts as that slot — the platform clamps
     *  inexact-alarm windows to ten minutes (Android 12+), so a firing can land that late and must
     *  still be recognized. Stays under half the smallest interval (30 min), which is what keeps a
     *  late firing from being mistaken for the NEXT slot. */
    internal val SLOT_TOLERANCE_MS = TimeUnit.MINUTES.toMillis(11)

    /** Ten minutes is what the platform grants anyway — asking for less just gets clamped. */
    private val WINDOW_MS = TimeUnit.MINUTES.toMillis(10)

    fun ensureScheduled(context: Context) {
        val peerStore = (context.applicationContext as HubApplication).container.syncPeerStore
        val enabled = peerStore.getPeers().filter { it.autoSyncEnabled }
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = alarmPendingIntent(context)
        if (enabled.isEmpty()) {
            alarmManager.cancel(pending)
            return
        }
        val now = System.currentTimeMillis()
        val nextSlot = enabled.minOf { nextSlotFor(now, it.autoSyncIntervalMinutes) }
        alarmManager.setWindow(AlarmManager.RTC_WAKEUP, nextSlot, WINDOW_MS, pending)
    }

    /** The first slot boundary after [now] for an interval — epoch-aligned, so two phones with the
     *  same interval compute the same boundary without ever talking about it. */
    internal fun nextSlotFor(now: Long, intervalMinutes: Int): Long {
        val interval = TimeUnit.MINUTES.toMillis(intervalMinutes.toLong().coerceAtLeast(1))
        return (now / interval + 1) * interval
    }

    /** Whether [now] sits close enough to a slot boundary of this interval to count as firing in
     *  that slot. */
    internal fun isAtSlot(now: Long, intervalMinutes: Int): Boolean {
        val interval = TimeUnit.MINUTES.toMillis(intervalMinutes.toLong().coerceAtLeast(1))
        val offset = now % interval
        return offset <= SLOT_TOLERANCE_MS || interval - offset <= SLOT_TOLERANCE_MS
    }

    private fun alarmPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, SyncAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}

/** The alarm's landing point — hands off to WorkManager immediately (a receiver may not hold a
 *  Bluetooth session open) and lets the worker re-arm the next slot. */
class SyncAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val request = OneTimeWorkRequestBuilder<AlignedSyncWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("aligned_peer_sync", ExistingWorkPolicy.KEEP, request)
    }
}

private const val TAG = "AlignedSyncWorker"

/**
 * Runs the sync attempts for the slot the alarm just fired in. The server side listens through
 * most of the slot window in one long accept; the client side pokes at the window several times,
 * because the two phones' alarms land at different points inside it. Peers whose interval doesn't
 * own this slot are left alone.
 */
class AlignedSyncWorker(
    context: Context,
    params: WorkerParameters
) : androidx.work.CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val peerStore = (applicationContext as HubApplication).container.syncPeerStore
        try {
            if (!SyncPrerequisites.bluetoothReady(applicationContext)) return Result.success()
            val now = System.currentTimeMillis()
            val due = peerStore.getPeers().filter {
                it.autoSyncEnabled && SyncAlarmScheduler.isAtSlot(now, it.autoSyncIntervalMinutes)
            }
            val orchestrator = SyncOrchestrator(applicationContext, peerStore)
            for (peer in due) {
                if (peer.isServerRole) {
                    val result = orchestrator.syncNow(peer, acceptTimeoutMs = SERVER_LISTEN_MS)
                    logIfFailed(peer, result)
                } else {
                    // A connect against a phone that isn't listening yet fails in moments; spread
                    // attempts across the window until one lands on the server's open accept.
                    for (delayMs in CLIENT_ATTEMPT_DELAYS_MS) {
                        kotlinx.coroutines.delay(delayMs)
                        val result = orchestrator.syncNow(peer)
                        if (result is SyncSessionResult.Success) break
                        logIfFailed(peer, result)
                    }
                }
            }
        } finally {
            // The chain must re-arm even when this slot's attempts failed or threw — a dead alarm
            // chain is silent auto-sync death until the periodic safety net notices.
            SyncAlarmScheduler.ensureScheduled(applicationContext)
        }
        return Result.success()
    }

    private fun logIfFailed(peer: PairedPeer, result: SyncSessionResult) {
        if (result is SyncSessionResult.Failure) {
            Logger.w(TAG, "Aligned sync with ${peer.label} didn't complete: ${result.reason}")
        }
    }

    companion object {
        /** Most of the slot window in one blocking accept — the two phones' alarms can land up to
         *  the platform's clamped ten-minute window apart, and the client's retries need something
         *  to land on for most of that spread. */
        private const val SERVER_LISTEN_MS = 480_000

        private val CLIENT_ATTEMPT_DELAYS_MS =
            listOf(0L, 30_000L, 30_000L, 60_000L, 60_000L, 60_000L, 90_000L, 90_000L)
    }
}
