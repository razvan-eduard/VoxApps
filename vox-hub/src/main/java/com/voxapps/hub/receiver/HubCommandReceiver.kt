package com.voxapps.hub.receiver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.voxapps.hub.HubApplication
import com.voxapps.hub.domain.sync.ImmediateSyncWorker
import com.voxapps.ipc.VoxCommand
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * Hub's end of the command bus — the only ops that flow SATELLITE → HUB (see
 * [VoxIpc.OP_LIST_SYNC_PEERS]/[VoxIpc.OP_ENQUEUE_PUSH]): a satellite's "sync with device"
 * multi-select asks who the paired devices are, then queues record uids for one of them. Everything
 * else in the contract flows the other way, which is why this receiver handles nothing else — a
 * plain `when(op)` over the two, guarded by the same shared signature permission
 * (`com.voxapps.vox.permission.COMMAND`) every satellite receiver uses.
 *
 * Both ops answer synchronously from [com.voxapps.hub.domain.sync.SyncPeerStore] — no goAsync, no
 * database. The queued push also enqueues one best-effort session attempt right away
 * ([ImmediateSyncWorker]); when the peer isn't reachable this moment, the uids simply ride the next
 * session of any kind.
 */
class HubCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != VoxIpc.ACTION_COMMAND) return
        val command = VoxCommand.fromJson(intent.getStringExtra(VoxIpc.EXTRA_PAYLOAD)) ?: return
        val peerStore = (context.applicationContext as HubApplication).container.syncPeerStore

        when (command.op) {
            VoxIpc.OP_LIST_SYNC_PEERS -> {
                val peers = JSONArray(peerStore.getPeers().map {
                    JSONObject().put("peerId", it.peerId).put("label", it.label)
                })
                setResult(Activity.RESULT_OK, VoxResult(ok = true, text = peers.toString()).toJson(), null)
            }

            VoxIpc.OP_ENQUEUE_PUSH -> {
                val peerId = command.peerId
                val sourcePackage = command.sourcePackage
                val uids = command.uids.orEmpty().filter { it.isNotBlank() }
                val peer = peerId?.let { peerStore.getPeer(it) }
                if (peer == null || sourcePackage.isNullOrBlank() || uids.isEmpty()) {
                    setResult(Activity.RESULT_OK, VoxResult(ok = false, text = "Unknown peer or empty push").toJson(), null)
                    return
                }
                val merged = (peer.pendingPushUidsByApp[sourcePackage].orEmpty() + uids).distinct()
                peerStore.upsertPeer(
                    peer.copy(pendingPushUidsByApp = peer.pendingPushUidsByApp + (sourcePackage to merged))
                )
                ImmediateSyncWorker.enqueue(context, peer.peerId)
                setResult(Activity.RESULT_OK, VoxResult(ok = true, text = "queued ${uids.size}").toJson(), null)
            }
        }
    }
}
