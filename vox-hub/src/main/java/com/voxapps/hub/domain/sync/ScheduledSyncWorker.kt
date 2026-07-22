package com.voxapps.hub.domain.sync

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.voxapps.hub.HubApplication
import com.voxapps.logging.Logger
import java.util.concurrent.TimeUnit

private const val TAG = "ScheduledSyncWorker"

/**
 * Runs every [ScheduledSyncScheduler.CHECK_INTERVAL_MINUTES], and for each [PairedPeer] with
 * [PairedPeer.autoSyncEnabled], attempts a sync if its own interval has elapsed since
 * [PairedPeer.lastAttemptedSyncAt]. Deliberately can't prompt for anything (no Bluetooth-enable
 * dialog, no runtime permission request) — a background Worker has no Activity to show one from — so
 * a peer whose prerequisites aren't met is just skipped for this tick, retried automatically next
 * time. Real reliability here depends on both paired phones happening to have this worker fire around
 * the same wall-clock window with Bluetooth already on; that's an inherent limitation of scheduling
 * two independent devices' WorkManager jobs, not something this worker can paper over.
 */
class ScheduledSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val peerStore = (applicationContext as HubApplication).container.syncPeerStore
        if (!bluetoothReady()) return Result.success()

        val orchestrator = SyncOrchestrator(applicationContext, peerStore)
        val now = System.currentTimeMillis()

        for (peer in peerStore.getPeers()) {
            if (!peer.autoSyncEnabled) continue
            val elapsedMs = now - (peer.lastAttemptedSyncAt ?: 0L)
            val intervalMs = TimeUnit.MINUTES.toMillis(peer.autoSyncIntervalMinutes.toLong())
            if (elapsedMs < intervalMs) continue

            val result = orchestrator.syncNow(peer)
            if (result is SyncSessionResult.Failure) {
                Logger.w(TAG, "Scheduled sync with ${peer.label} didn't complete: ${result.reason}")
            }
        }
        return Result.success()
    }

    private fun bluetoothReady(): Boolean {
        val adapter = (applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return false
        val connectPermission = if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH
        if (ContextCompat.checkSelfPermission(applicationContext, connectPermission) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        return try {
            adapter.isEnabled
        } catch (e: SecurityException) {
            false
        }
    }
}
