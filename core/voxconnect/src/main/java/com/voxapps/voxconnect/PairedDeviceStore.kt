package com.voxapps.voxconnect

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/** A desktop VoxConnect instance that has completed pairing. [sessionKey] is the real secret — every
 *  request from this device is [AesGcmCipher]-encrypted with it. */
data class PairedDevice(
    val deviceId: String,
    val label: String,
    val sessionKey: ByteArray,
    val pairedAt: Long
)

/**
 * Persists confirmed [PairedDevice]s. Backed by EncryptedSharedPreferences (Keystore-backed
 * MasterKey), same pattern as vox-hub's `SyncPeerStore` / each satellite's `DbKey` — [PairedDevice.sessionKey]
 * is a real secret, not app config, so it doesn't belong in plain DataStore.
 */
class PairedDeviceStore(context: Context) {

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

    fun getDevices(): List<PairedDevice> {
        val raw = prefs.getString(KEY_DEVICES, null) ?: return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).map { array.getJSONObject(it).toPairedDevice() }
    }

    fun getDevice(deviceId: String): PairedDevice? = getDevices().firstOrNull { it.deviceId == deviceId }

    fun upsertDevice(device: PairedDevice) {
        val updated = getDevices().filterNot { it.deviceId == device.deviceId } + device
        saveDevices(updated)
    }

    fun revokeDevice(deviceId: String) {
        saveDevices(getDevices().filterNot { it.deviceId == deviceId })
    }

    /** Renames an already-paired device (its stored [PairedDevice.label] only — deviceId/
     *  sessionKey/pairedAt are untouched, so the desktop's existing session keeps working). A
     *  no-op if [deviceId] isn't currently paired. */
    fun renameDevice(deviceId: String, newLabel: String) {
        saveDevices(getDevices().map { if (it.deviceId == deviceId) it.copy(label = newLabel) else it })
    }

    private fun saveDevices(devices: List<PairedDevice>) {
        val array = JSONArray(devices.map { it.toJson() })
        prefs.edit().putString(KEY_DEVICES, array.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "vox-connect-paired-devices"
        private const val KEY_DEVICES = "paired_devices_json"
    }
}

private fun PairedDevice.toJson(): JSONObject = JSONObject().apply {
    put("deviceId", deviceId)
    put("label", label)
    put("sessionKeyBase64", AesGcmCipher.keyToBase64(sessionKey))
    put("pairedAt", pairedAt)
}

private fun JSONObject.toPairedDevice(): PairedDevice = PairedDevice(
    deviceId = optString("deviceId"),
    label = optString("label"),
    sessionKey = AesGcmCipher.keyFromBase64(optString("sessionKeyBase64")),
    pairedAt = optLong("pairedAt")
)
