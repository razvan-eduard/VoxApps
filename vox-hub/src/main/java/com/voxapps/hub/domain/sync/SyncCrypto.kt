package com.voxapps.hub.domain.sync

import com.voxapps.voxconnect.AesGcmCipher

/**
 * App-level AES-256-GCM for every message [SecureSyncChannel] sends over the raw, unbonded,
 * insecure RFCOMM socket [BluetoothSyncTransport] opens — this is what actually protects a sync
 * session's contents, standing in for the OS-level Bluetooth pairing this design deliberately skips
 * (see [BluetoothPeerResolver]'s doc comment). The key itself is [PairedPeer.sharedKeyBase64],
 * established once over NFC and never sent over Bluetooth. The cipher itself is
 * [AesGcmCipher] — one implementation, wire-compatible with what this object always produced.
 */
object SyncCrypto {
    fun encrypt(keyBytes: ByteArray, plaintext: ByteArray): ByteArray =
        AesGcmCipher.encryptBytes(keyBytes, plaintext)

    fun decrypt(keyBytes: ByteArray, payload: ByteArray): ByteArray =
        AesGcmCipher.decryptBytes(keyBytes, payload)
}
