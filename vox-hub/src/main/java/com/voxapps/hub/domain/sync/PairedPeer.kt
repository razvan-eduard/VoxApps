package com.voxapps.hub.domain.sync

/**
 * A remembered peer device this phone has NFC-paired with for peer-to-peer data sync. Persisted in
 * [SyncPeerStore]. Categories/layers travel by name in the actual sync payloads (see each satellite's
 * `*SyncHandler`), so [scopeNamesByApp] does too — an absent or empty list for a given app domain
 * means "sync everything," matching the export handlers' own `scopeNames == null` convention.
 *
 * [isServerRole]/[bluetoothMac] are asymmetric by design: Android forbids an app from reading its own
 * Bluetooth MAC address (privacy restriction since API 23), so only the CLIENT side ever resolves and
 * stores a MAC — the SERVER side just listens on a fixed app UUID and never needs to know its own
 * address. Which side is which is decided once, during NFC pairing (see [PairingHceService] vs
 * [NfcPairingReader]), and persists for every future sync with this peer (menu-triggered or
 * scheduled), not just the pairing tap itself.
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
     *  interval cadence must not attempt again every 15 minutes (WorkManager's periodic floor) just
     *  because the peer was unreachable at the last attempt. */
    val lastAttemptedSyncAt: Long? = null
) {
    companion object {
        const val DEFAULT_AUTO_SYNC_INTERVAL_MINUTES = 60
    }
}
