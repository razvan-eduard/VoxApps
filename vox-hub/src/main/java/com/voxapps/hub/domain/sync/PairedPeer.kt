package com.voxapps.hub.domain.sync

/**
 * A remembered peer device this phone has NFC-paired with for peer-to-peer data sync. Persisted in
 * [SyncPeerStore]. Containers travel by name in the actual sync payloads (bank accounts for
 * Expenses, categories for Notes, calendars for Calendar — see each satellite's `*SyncHandler`), so
 * [scopeNamesByApp] does too. Sharing is opt-in per container: an app with no entry here has
 * nothing ticked, and the orchestrator sends its handler an EMPTY scope list (= share nothing);
 * only records the user pushed by hand still travel. The handlers' `scopeNames == null` ("share
 * everything") is never sent for a paired peer — it exists for the satellites' own ALL sync level,
 * which ignores scope entirely.
 *
 * [isServerRole]/[bluetoothMac] are asymmetric by design: Android forbids an app from reading its own
 * Bluetooth MAC address (privacy restriction since API 23), so only the CLIENT side ever resolves and
 * stores a MAC — the SERVER side just listens on a fixed app UUID and never needs to know its own
 * address. Which side is which is decided once, during NFC pairing (see [PairingHceService] vs
 * [NfcPairingReader]), and persists for every future sync with this peer (menu-triggered or
 * scheduled), not just the pairing tap itself.
 *
 * [label] is the peer's own self-declared device name (see [SyncPeerStore.localDeviceName]),
 * refreshed from every pairing tap and every sync session's handshake — it is also what merge
 * stamps onto inserted rows as their provenance display name.
 */
data class PairedPeer(
    val peerId: String,
    val label: String,
    val isServerRole: Boolean,
    val sharedKeyBase64: String,
    val bluetoothMac: String? = null,
    val pairedAt: Long,
    val autoSyncEnabled: Boolean = false,
    val autoSyncIntervalMinutes: Int = DEFAULT_AUTO_SYNC_INTERVAL_MINUTES,
    val lastSyncAtByApp: Map<String, Long> = emptyMap(),
    val scopeNamesByApp: Map<String, List<String>> = emptyMap(),
    /** When [ScheduledSyncWorker] last *attempted* a sync with this peer, success or not — distinct
     *  from [lastSyncAtByApp] (which only advances per-app on a successful merge) because the
     *  interval cadence must not attempt again on every check tick just because the peer was
     *  unreachable at the last attempt. */
    val lastAttemptedSyncAt: Long? = null,
    /** Record uids queued by each satellite's "sync with device" multi-select action, keyed by the
     *  satellite's package — forced into the next session's export with this peer regardless of
     *  sync level or scope, and drained per app once the peer acknowledges it merged them. */
    val pendingPushUidsByApp: Map<String, List<String>> = emptyMap()
) {
    companion object {
        const val DEFAULT_AUTO_SYNC_INTERVAL_MINUTES = 60
    }
}
