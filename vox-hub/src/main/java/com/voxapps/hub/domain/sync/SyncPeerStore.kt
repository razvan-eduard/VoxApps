package com.voxapps.hub.domain.sync

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Persists this device's own sync identity and its remembered [PairedPeer]s. Backed by
 * EncryptedSharedPreferences (master key in the Android Keystore, mirrors `DbKey`'s pattern in
 * Expenses/Notes/Calendar) rather than plain DataStore, since [PairedPeer.sharedKeyBase64] is a
 * real secret — it's the app-level AES key that encrypts every future sync payload with that peer,
 * standing in for OS-level Bluetooth bonding, which this design deliberately skips.
 */
class SyncPeerStore(context: Context) {

    private val prefs = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /** This device's own stable identity, shown to every peer it pairs with — generated once, never
     *  tied to a Bluetooth MAC (which itself is never even readable, see [PairedPeer]'s doc comment). */
    val localPeerId: String
        get() = prefs.getString(KEY_LOCAL_PEER_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_LOCAL_PEER_ID, it).apply()
        }

    fun getPeers(): List<PairedPeer> {
        val raw = prefs.getString(KEY_PEERS, null) ?: return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).map { array.getJSONObject(it).toPairedPeer() }
    }

    fun getPeer(peerId: String): PairedPeer? = getPeers().firstOrNull { it.peerId == peerId }

    /** Inserts a new peer or replaces the existing one with the same [PairedPeer.peerId]. */
    fun upsertPeer(peer: PairedPeer) {
        val updated = getPeers().filterNot { it.peerId == peer.peerId } + peer
        savePeers(updated)
    }

    fun removePeer(peerId: String) {
        savePeers(getPeers().filterNot { it.peerId == peerId })
    }

    private fun savePeers(peers: List<PairedPeer>) {
        val array = JSONArray(peers.map { it.toJson() })
        prefs.edit().putString(KEY_PEERS, array.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "vox-hub-sync-secure"
        private const val KEY_LOCAL_PEER_ID = "local_peer_id"
        private const val KEY_PEERS = "paired_peers_json"
    }
}

private fun PairedPeer.toJson(): JSONObject = JSONObject().apply {
    put("peerId", peerId)
    put("label", label)
    put("isServerRole", isServerRole)
    put("sharedKeyBase64", sharedKeyBase64)
    put("bluetoothMac", bluetoothMac)
    put("pairedAt", pairedAt)
    put("autoSyncEnabled", autoSyncEnabled)
    put("autoSyncIntervalMinutes", autoSyncIntervalMinutes)
    put("lastSyncAtByApp", JSONObject(lastSyncAtByApp))
    put("scopeNamesByApp", JSONObject(scopeNamesByApp.mapValues { (_, names) -> JSONArray(names) }))
}

private fun JSONObject.toPairedPeer(): PairedPeer {
    val lastSyncJson = optJSONObject("lastSyncAtByApp") ?: JSONObject()
    val lastSyncAtByApp = lastSyncJson.keys().asSequence().associateWith { lastSyncJson.getLong(it) }

    val scopeJson = optJSONObject("scopeNamesByApp") ?: JSONObject()
    val scopeNamesByApp = scopeJson.keys().asSequence().associateWith { key ->
        val arr = scopeJson.getJSONArray(key)
        (0 until arr.length()).map { arr.getString(it) }
    }

    return PairedPeer(
        peerId = optString("peerId"),
        label = optString("label"),
        isServerRole = optBoolean("isServerRole"),
        sharedKeyBase64 = optString("sharedKeyBase64"),
        bluetoothMac = if (has("bluetoothMac") && !isNull("bluetoothMac")) optString("bluetoothMac") else null,
        pairedAt = optLong("pairedAt"),
        autoSyncEnabled = optBoolean("autoSyncEnabled", false),
        autoSyncIntervalMinutes = optInt("autoSyncIntervalMinutes", PairedPeer.DEFAULT_AUTO_SYNC_INTERVAL_MINUTES),
        lastSyncAtByApp = lastSyncAtByApp,
        scopeNamesByApp = scopeNamesByApp
    )
}
