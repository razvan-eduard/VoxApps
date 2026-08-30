package com.voxapps.hub.domain.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.voxapps.hub.HubApplication
import com.voxapps.logging.Logger

private const val TAG = "ImmediateSyncWorker"

/**
 * One best-effort sync session with one peer, right now — what a satellite's "sync with device"
 * push enqueues (see [com.voxapps.hub.receiver.HubCommandReceiver]). If the peer isn't reachable
 * this moment the attempt simply fails and the queued uids stay on [PairedPeer.pendingPushUidsByApp],
 * riding whichever session — manual, scheduled, or a later push — next completes.
 */
class ImmediateSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val peerId = inputData.getString(KEY_PEER_ID) ?: return Result.success()
        val peerStore = (applicationContext as HubApplication).container.syncPeerStore
        val peer = peerStore.getPeer(peerId) ?: return Result.success()
        if (!SyncPrerequisites.bluetoothReady(applicationContext)) return Result.success()

        val result = SyncOrchestrator(applicationContext, peerStore).syncNow(peer)
        if (result is SyncSessionResult.Failure) {
            Logger.w(TAG, "Push-triggered sync with ${peer.label} didn't complete: ${result.reason}")
        }
        return Result.success()
    }

    companion object {
        private const val KEY_PEER_ID = "peerId"

        /** One in-flight attempt per peer; a second push while one runs just keeps the queue riding
         *  the session already underway. */
        fun enqueue(context: Context, peerId: String) {
            val request = OneTimeWorkRequestBuilder<ImmediateSyncWorker>()
                .setInputData(Data.Builder().putString(KEY_PEER_ID, peerId).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("push_sync_$peerId", ExistingWorkPolicy.KEEP, request)
        }
    }
}
