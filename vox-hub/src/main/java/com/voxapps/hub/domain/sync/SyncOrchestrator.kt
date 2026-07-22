package com.voxapps.hub.domain.sync

import android.content.Context
import android.util.Base64
import com.voxapps.ipc.VoxAppInfo
import com.voxapps.ipc.VoxAppsDiscovery
import com.voxapps.ipc.VoxDataTransferClient
import com.voxapps.ipc.VoxIpc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class AppSyncResult(
    val packageName: String,
    val label: String,
    val success: Boolean,
    val summary: String
)

sealed interface SyncSessionResult {
    data class Success(val appResults: List<AppSyncResult>) : SyncSessionResult
    data class Failure(val reason: String) : SyncSessionResult
}

/**
 * Drives one full sync session with a [PairedPeer]: opens the Bluetooth transport (see
 * [BluetoothSyncTransport]), agrees on which installed apps both sides can sync, then for each one
 * exchanges an [VoxIpc.OP_SYNC_EXPORT] delta and applies the peer's via [VoxIpc.OP_SYNC_MERGE] —
 * genuinely bidirectional, since both sides run the same export-then-merge dance for every app in
 * the same session, not a push or a pull. Callable identically from a manual "Sync now" tap
 * ([SyncScreen]) or [ScheduledSyncWorker]; the only difference is who calls it and when.
 */
class SyncOrchestrator(
    private val context: Context,
    private val peerStore: SyncPeerStore
) {
    suspend fun syncNow(peer: PairedPeer): SyncSessionResult = withContext(Dispatchers.IO) {
        val result = performSync(peer)
        val attemptedAt = System.currentTimeMillis()
        val updatedPeer = when (result) {
            is SyncSessionResult.Success -> {
                val newWatermarks = peer.lastSyncAtByApp.toMutableMap()
                result.appResults.filter { it.success }.forEach { newWatermarks[it.packageName] = attemptedAt }
                peer.copy(lastSyncAtByApp = newWatermarks, lastAttemptedSyncAt = attemptedAt)
            }
            is SyncSessionResult.Failure -> peer.copy(lastAttemptedSyncAt = attemptedAt)
        }
        peerStore.upsertPeer(updatedPeer)
        result
    }

    private fun performSync(peer: PairedPeer): SyncSessionResult {
        val keyBytes = try {
            Base64.decode(peer.sharedKeyBase64, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            return SyncSessionResult.Failure("Corrupted pairing key")
        }

        val socket = if (peer.isServerRole) {
            BluetoothSyncTransport.listenAndAccept(context, ACCEPT_TIMEOUT_MS)
        } else {
            val mac = peer.bluetoothMac
                ?: return SyncSessionResult.Failure("No Bluetooth address known yet for this device")
            BluetoothSyncTransport.connect(context, mac)
        } ?: return SyncSessionResult.Failure("Couldn't establish a Bluetooth connection")

        return SecureSyncChannel(socket, keyBytes).use { channel ->
            try {
                runSession(channel, peer)
            } catch (e: Exception) {
                SyncSessionResult.Failure(e.message ?: "Sync session failed")
            }
        }
    }

    private fun runSession(channel: SecureSyncChannel, peer: PairedPeer): SyncSessionResult {
        val localApps = VoxAppsDiscovery.discover(context)
            .filter { VoxIpc.OP_SYNC_EXPORT in it.actions && VoxIpc.OP_SYNC_MERGE in it.actions }
        val localPackages = localApps.map { it.packageName }.sorted()

        // The client speaks first (proposes what it can sync); the server intersects with its own
        // installed set and echoes back the agreed list — so both sides iterate an identical,
        // deterministically-ordered set of packages for the rest of the session.
        val agreedPackages: List<String> = if (!peer.isServerRole) {
            channel.send(JSONObject().put("apps", JSONArray(localPackages)).toString())
            val response = JSONObject(channel.receive())
            response.getJSONArray("apps").toStringList()
        } else {
            val request = JSONObject(channel.receive())
            val requested = request.getJSONArray("apps").toStringList()
            val agreed = requested.filter { it in localPackages }.sorted()
            channel.send(JSONObject().put("apps", JSONArray(agreed)).toString())
            agreed
        }

        val results = agreedPackages.map { packageName ->
            syncOneApp(channel, peer, localApps.first { it.packageName == packageName })
        }
        return SyncSessionResult.Success(results)
    }

    private fun syncOneApp(channel: SecureSyncChannel, peer: PairedPeer, appInfo: VoxAppInfo): AppSyncResult {
        val since = peer.lastSyncAtByApp[appInfo.packageName] ?: 0L
        val scopeNames = peer.scopeNamesByApp[appInfo.packageName]

        val localExport = requestSuspending { VoxDataTransferClient.requestSyncExport(context, appInfo.packageName, since, scopeNames) }
        val localExportJson = if (localExport?.ok == true) localExport.text else EMPTY_DELTA

        // Client sends first, then waits for the server's delta; server does the mirror image — this
        // fixed ordering (tied to the role NFC pairing already assigned) is what keeps both sides from
        // trying to read a socket that neither has written to yet.
        val peerDeltaJson = if (!peer.isServerRole) {
            channel.send(localExportJson)
            channel.receive()
        } else {
            val incoming = channel.receive()
            channel.send(localExportJson)
            incoming
        }

        val mergeResult = requestSuspending { VoxDataTransferClient.requestSyncMerge(context, appInfo.packageName, peerDeltaJson) }
        val success = localExport?.ok == true && mergeResult?.ok == true
        val summary = when {
            localExport?.ok != true -> "Export failed: ${localExport?.text ?: "no response"}"
            mergeResult?.ok != true -> "Merge failed: ${mergeResult?.text ?: "no response"}"
            else -> mergeResult.text
        }
        return AppSyncResult(appInfo.packageName, appInfo.label, success, summary)
    }

    /** [VoxDataTransferClient]'s functions are suspend (ordered-broadcast IPC) but this whole
     *  orchestrator otherwise runs synchronous blocking socket I/O on [Dispatchers.IO] — bridges the
     *  two without spawning a nested coroutine scope. */
    private fun <T> requestSuspending(block: suspend () -> T): T = runBlocking { block() }

    companion object {
        private const val ACCEPT_TIMEOUT_MS = 60_000
        private const val EMPTY_DELTA = """{"entries":[],"tombstones":[]}"""
    }
}

private fun JSONArray.toStringList(): List<String> = (0 until length()).map { getString(it) }
