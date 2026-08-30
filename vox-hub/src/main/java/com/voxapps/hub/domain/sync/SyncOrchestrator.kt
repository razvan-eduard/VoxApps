package com.voxapps.hub.domain.sync

import android.content.Context
import android.util.Base64
import com.voxapps.datahygiene.SyncDeltaKeys
import com.voxapps.ipc.VoxAppInfo
import com.voxapps.ipc.VoxAppsDiscovery
import com.voxapps.ipc.VoxDataTransferClient
import com.voxapps.ipc.VoxIpc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class AppSyncResult(
    val packageName: String,
    val label: String,
    /** Both directions landed: this side's delta was acknowledged by the peer AND the peer's delta
     *  merged here. What the sync card reports. */
    val success: Boolean,
    val summary: String,
    /** The watermark this app has EARNED — the largest updatedAt/deletedAt this side exported, set
     *  only once the peer acknowledged applying the delta. Null means the watermark must not move:
     *  either nothing was sent, or nothing is known to have landed, and an unmoved watermark is
     *  what makes the same records travel again next session instead of falling into a silent
     *  hole. */
    val watermarkTo: Long? = null,
    /** Whether the peer acknowledged applying this side's delta — the condition for draining this
     *  app's queued manual pushes. */
    val peerAccepted: Boolean = false
)

sealed interface SyncSessionResult {
    data class Success(val appResults: List<AppSyncResult>) : SyncSessionResult
    data class Failure(val reason: String) : SyncSessionResult
}

/**
 * Drives one full sync session with a [PairedPeer]: opens the Bluetooth transport (see
 * [BluetoothSyncTransport]), exchanges a version-checked handshake (protocol version, device name,
 * app list), then for each app both sides can sync runs a paged, acknowledged exchange — genuinely
 * bidirectional, since both sides stream their [VoxIpc.OP_SYNC_EXPORT] pages and apply the peer's
 * via [VoxIpc.OP_SYNC_MERGE] in the same session.
 *
 * Per app, per direction: the sender streams delta pages (bounded by [PAGE_SIZE], so no single
 * broadcast or frame carries an unbounded first sync); the receiver merges each page as it arrives
 * and answers with one acknowledgement frame carrying its merge outcome. A side only advances its
 * own per-app watermark — to the largest timestamp it actually exported, not to a clock reading —
 * once the PEER confirms the delta was applied, so a peer that was locked, timed out, or crashed
 * mid-merge gets the same records again next session instead of never.
 *
 * Callable identically from a manual "Sync now" tap ([SyncScreen]), [ScheduledSyncWorker], or a
 * satellite-triggered push ([ImmediateSyncWorker]); the only difference is who calls it and when.
 */
class SyncOrchestrator(
    private val context: Context,
    private val peerStore: SyncPeerStore
) {
    /**
     * The whole call chain below ([performSync] -> [runSession] -> [syncOneApp]) is `suspend` rather
     * than plain functions bridged by `runBlocking`. The IPC calls are suspending, and wrapping each
     * in `runBlocking` started a *root* coroutine — not a child of this job — so cancelling a sync
     * left the in-flight export/merge request running to completion. Suspending straight through
     * makes each IPC round-trip a real cancellation point. (The socket reads in between are blocking
     * and stay uninterruptible; that is inherent to BluetoothSocket, and the accept path is bounded
     * by the caller-chosen accept timeout.)
     */
    suspend fun syncNow(peer: PairedPeer, acceptTimeoutMs: Int = ACCEPT_TIMEOUT_MS): SyncSessionResult =
        withContext(Dispatchers.IO) {
            val session = performSync(peer, acceptTimeoutMs)
            val attemptedAt = System.currentTimeMillis()
            // Re-read rather than trust the argument: the session may run for a while, and a scope
            // or queue edit made meanwhile must not be clobbered by a stale copy.
            val stored = peerStore.getPeer(peer.peerId) ?: peer
            val updatedPeer = when (session) {
                is SessionOutcome.Completed -> {
                    val newWatermarks = stored.lastSyncAtByApp.toMutableMap()
                    val newPending = stored.pendingPushUidsByApp.toMutableMap()
                    session.appResults.forEach { result ->
                        result.watermarkTo?.let { newWatermarks[result.packageName] = it }
                        if (result.peerAccepted) newPending.remove(result.packageName)
                    }
                    stored.copy(
                        label = session.peerName ?: stored.label,
                        lastSyncAtByApp = newWatermarks,
                        pendingPushUidsByApp = newPending,
                        lastAttemptedSyncAt = attemptedAt
                    )
                }
                is SessionOutcome.Failed -> stored.copy(lastAttemptedSyncAt = attemptedAt)
            }
            peerStore.upsertPeer(updatedPeer)
            when (session) {
                is SessionOutcome.Completed -> SyncSessionResult.Success(session.appResults)
                is SessionOutcome.Failed -> SyncSessionResult.Failure(session.reason)
            }
        }

    private sealed interface SessionOutcome {
        data class Completed(val appResults: List<AppSyncResult>, val peerName: String?) : SessionOutcome
        data class Failed(val reason: String) : SessionOutcome
    }

    private suspend fun performSync(peer: PairedPeer, acceptTimeoutMs: Int): SessionOutcome {
        val keyBytes = try {
            Base64.decode(peer.sharedKeyBase64, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            return SessionOutcome.Failed("Corrupted pairing key")
        }

        val socket = if (peer.isServerRole) {
            BluetoothSyncTransport.listenAndAccept(context, acceptTimeoutMs)
        } else {
            val mac = peer.bluetoothMac
                ?: return SessionOutcome.Failed("No Bluetooth address known yet for this device")
            BluetoothSyncTransport.connect(context, mac)
        } ?: return SessionOutcome.Failed("Couldn't establish a Bluetooth connection")

        return SecureSyncChannel(socket, keyBytes).use { channel ->
            try {
                runSession(channel, peer)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Rethrown, not converted to a failure — a cancelled sync is the user cancelling,
                // not the peer failing.
                throw e
            } catch (e: Exception) {
                SessionOutcome.Failed(e.message ?: "Sync session failed")
            }
        }
    }

    private suspend fun runSession(channel: SecureSyncChannel, peer: PairedPeer): SessionOutcome {
        val localApps = VoxAppsDiscovery.discover(context)
            .filter { VoxIpc.OP_SYNC_EXPORT in it.actions && VoxIpc.OP_SYNC_MERGE in it.actions }
        val localPackages = localApps.map { it.packageName }.sorted()
        val localHello = JSONObject()
            .put(KEY_PROTO, PROTOCOL_VERSION)
            .put(KEY_NAME, peerStore.localDeviceName)
            .put(KEY_APPS, JSONArray(localPackages))

        // The client speaks first (proposes what it can sync); the server intersects with its own
        // installed set and echoes back the agreed list — so both sides iterate an identical,
        // deterministically-ordered set of packages for the rest of the session. Both frames carry
        // the protocol version, and a mismatch ends the session before any data moves: the paged,
        // acknowledged exchange below has no meaning to a build that speaks a different protocol,
        // and half-understanding it would deadlock both phones mid-frame.
        val peerHello: JSONObject
        val agreedPackages: List<String>
        if (!peer.isServerRole) {
            channel.send(localHello.toString())
            peerHello = JSONObject(channel.receive())
            if (peerHello.optInt(KEY_PROTO, 1) != PROTOCOL_VERSION) {
                return SessionOutcome.Failed("The other phone runs an incompatible Vox Hub version — update both")
            }
            agreedPackages = peerHello.getJSONArray(KEY_APPS).toStringList()
        } else {
            peerHello = JSONObject(channel.receive())
            val requested = peerHello.getJSONArray(KEY_APPS).toStringList()
            val protoOk = peerHello.optInt(KEY_PROTO, 1) == PROTOCOL_VERSION
            agreedPackages = if (protoOk) requested.filter { it in localPackages }.sorted() else emptyList()
            channel.send(localHello.put(KEY_APPS, JSONArray(agreedPackages)).toString())
            if (!protoOk) {
                return SessionOutcome.Failed("The other phone runs an incompatible Vox Hub version — update both")
            }
        }
        val peerName = peerHello.optString(KEY_NAME).takeIf { it.isNotBlank() }

        val results = agreedPackages.map { packageName ->
            syncOneApp(channel, peer, peerName, localApps.first { it.packageName == packageName })
        }
        return SessionOutcome.Completed(results, peerName)
    }

    private suspend fun syncOneApp(
        channel: SecureSyncChannel,
        peer: PairedPeer,
        peerName: String?,
        appInfo: VoxAppInfo
    ): AppSyncResult {
        // Client streams first, then merges the server's stream; the server mirrors it — the same
        // fixed, role-tied ordering the handshake uses, which is what keeps both sides from reading
        // a socket neither has written to yet.
        val export: ExportOutcome
        val peerAck: JSONObject
        val localMerge: MergeOutcome
        if (!peer.isServerRole) {
            export = streamExportPages(channel, peer, appInfo.packageName)
            peerAck = JSONObject(channel.receive())
            localMerge = receiveAndMergePages(channel, peer, peerName, appInfo.packageName)
            channel.send(localMerge.toAckJson())
        } else {
            localMerge = receiveAndMergePages(channel, peer, peerName, appInfo.packageName)
            channel.send(localMerge.toAckJson())
            export = streamExportPages(channel, peer, appInfo.packageName)
            peerAck = JSONObject(channel.receive())
        }

        val peerAccepted = peerAck.optBoolean(KEY_ACK_OK, false)
        val success = export.ok && peerAccepted && localMerge.ok
        val summary = when {
            !export.ok -> "Export failed: ${export.error ?: "no response"}"
            !peerAccepted -> "Peer couldn't apply: ${peerAck.optString(KEY_ACK_ERROR).ifBlank { "no detail" }}"
            !localMerge.ok -> "Merge failed: ${localMerge.error ?: "no response"}"
            else -> "Received ${localMerge.inserted} new, ${localMerge.updated} updated, ${localMerge.deleted} deleted"
        }
        return AppSyncResult(
            packageName = appInfo.packageName,
            label = appInfo.label,
            success = success,
            summary = summary,
            // maxTimestamp is null when the delta was empty — nothing sent, nothing to move past.
            watermarkTo = if (export.ok && peerAccepted) export.maxTimestamp else null,
            peerAccepted = export.ok && peerAccepted
        )
    }

    private data class ExportOutcome(val ok: Boolean, val maxTimestamp: Long?, val error: String?)

    /**
     * Streams this side's delta as page frames. The peer reads pages until one arrives without
     * [SyncDeltaKeys.NEXT_CURSOR], so every path — including a satellite that refused to export
     * (locked) or answered garbage — must still end the stream with one well-formed terminal frame
     * to keep the two phones in lockstep.
     */
    private suspend fun streamExportPages(
        channel: SecureSyncChannel,
        peer: PairedPeer,
        packageName: String
    ): ExportOutcome {
        val since = peer.lastSyncAtByApp[packageName] ?: 0L
        // Sharing is opt-in per container: no scope configured means nothing is shared (the
        // handlers' null-scope "everything" is reserved for their own ALL level, which ignores
        // scope). Queued manual pushes travel regardless.
        val scopeNames = peer.scopeNamesByApp[packageName] ?: emptyList()
        val forcedUids = peer.pendingPushUidsByApp[packageName]?.takeIf { it.isNotEmpty() }

        var cursor: String? = null
        var maxTimestamp: Long? = null
        while (true) {
            val page = VoxDataTransferClient.requestSyncExport(
                context, packageName, since,
                scopeNames = scopeNames, forcedUids = forcedUids,
                cursor = cursor, pageSize = PAGE_SIZE
            )
            if (page?.ok != true) {
                channel.send(SyncDeltaKeys.EMPTY_DELTA)
                return ExportOutcome(ok = false, maxTimestamp = null, error = page?.text ?: "no response")
            }
            val pageJson = try {
                JSONObject(page.text)
            } catch (e: Exception) {
                channel.send(SyncDeltaKeys.EMPTY_DELTA)
                return ExportOutcome(ok = false, maxTimestamp = null, error = "malformed export page")
            }
            maxTimestamp = maxOf(maxTimestamp ?: Long.MIN_VALUE, pageJson.maxExportedTimestamp() ?: Long.MIN_VALUE)
                .takeIf { it != Long.MIN_VALUE }
            channel.send(page.text)
            cursor = pageJson.optString(SyncDeltaKeys.NEXT_CURSOR).takeIf { it.isNotEmpty() } ?: break
        }
        return ExportOutcome(ok = true, maxTimestamp = maxTimestamp, error = null)
    }

    private data class MergeOutcome(
        val ok: Boolean,
        val inserted: Int,
        val updated: Int,
        val deleted: Int,
        val error: String?
    ) {
        fun toAckJson(): String = JSONObject().apply {
            put(KEY_ACK, true)
            put(KEY_ACK_OK, ok)
            put(SyncDeltaKeys.INSERTED, inserted)
            put(SyncDeltaKeys.UPDATED, updated)
            put(SyncDeltaKeys.DELETED, deleted)
            error?.let { put(KEY_ACK_ERROR, it) }
        }.toString()
    }

    /** Reads the peer's page frames until the terminal one, handing each to the local satellite's
     *  merge as it arrives — bounded memory, bounded broadcast size, no waiting for a full delta.
     *  A failed page doesn't stop the reads (the stream must drain to stay in lockstep), it only
     *  makes the acknowledgement negative so the peer re-sends everything next session. */
    private suspend fun receiveAndMergePages(
        channel: SecureSyncChannel,
        peer: PairedPeer,
        peerName: String?,
        packageName: String
    ): MergeOutcome {
        var ok = true
        var inserted = 0
        var updated = 0
        var deleted = 0
        var error: String? = null
        while (true) {
            val frame = channel.receive()
            val frameJson = JSONObject(frame)
            val result = VoxDataTransferClient.requestSyncMerge(
                context, packageName, frame,
                sourceDeviceId = peer.peerId,
                sourceDeviceName = peerName ?: peer.label
            )
            if (result?.ok == true) {
                val counts = try {
                    JSONObject(result.text)
                } catch (e: Exception) {
                    JSONObject()
                }
                inserted += counts.optInt(SyncDeltaKeys.INSERTED)
                updated += counts.optInt(SyncDeltaKeys.UPDATED)
                deleted += counts.optInt(SyncDeltaKeys.DELETED)
            } else if (ok) {
                ok = false
                error = result?.text ?: "no response"
            }
            if (frameJson.optString(SyncDeltaKeys.NEXT_CURSOR).isEmpty()) break
        }
        return MergeOutcome(ok, inserted, updated, deleted, error)
    }

    companion object {
        private const val ACCEPT_TIMEOUT_MS = 60_000

        /** Bumped on any change to the frame sequence or their meaning — a mismatch fails the
         *  handshake instead of deadlocking two phones that disagree on who reads next. */
        private const val PROTOCOL_VERSION = 2

        /** Records per export page — sized so a page's JSON stays far under the binder transaction
         *  budget the broadcast to the satellite has to fit in. */
        private const val PAGE_SIZE = 200

        private const val KEY_PROTO = "proto"
        private const val KEY_NAME = "name"
        private const val KEY_APPS = "apps"
        private const val KEY_ACK = "ack"
        private const val KEY_ACK_OK = "ok"
        private const val KEY_ACK_ERROR = "error"

        /** The largest updatedAt/deletedAt a delta page carries — what an earned watermark advances
         *  to (see [AppSyncResult.watermarkTo]). */
        private fun JSONObject.maxExportedTimestamp(): Long? {
            var max = Long.MIN_VALUE
            optJSONArray(SyncDeltaKeys.ENTRIES)?.let { entries ->
                for (i in 0 until entries.length()) {
                    max = maxOf(max, entries.optJSONObject(i)?.optLong(SyncDeltaKeys.UPDATED_AT) ?: Long.MIN_VALUE)
                }
            }
            optJSONArray(SyncDeltaKeys.TOMBSTONES)?.let { tombstones ->
                for (i in 0 until tombstones.length()) {
                    max = maxOf(max, tombstones.optJSONObject(i)?.optLong(SyncDeltaKeys.DELETED_AT) ?: Long.MIN_VALUE)
                }
            }
            return max.takeIf { it != Long.MIN_VALUE }
        }
    }
}

private fun JSONArray.toStringList(): List<String> = (0 until length()).map { getString(it) }
