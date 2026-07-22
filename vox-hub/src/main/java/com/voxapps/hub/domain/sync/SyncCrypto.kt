package com.voxapps.hub.domain.sync

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * App-level AES-256-GCM for every message [SecureSyncChannel] sends over the raw, unbonded,
 * insecure RFCOMM socket [BluetoothSyncTransport] opens — this is what actually protects a sync
 * session's contents, standing in for the OS-level Bluetooth pairing this design deliberately skips
 * (see [BluetoothPeerResolver]'s doc comment). The key itself is [PairedPeer.sharedKeyBase64],
 * established once over NFC and never sent over Bluetooth.
 */
object SyncCrypto {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH_BYTES = 12
    private const val TAG_LENGTH_BITS = 128

    /** Prefixes a fresh random IV to the ciphertext+tag so [decrypt] is self-contained per message —
     *  a GCM key must never reuse an IV, and a random 96-bit IV makes a collision astronomically
     *  unlikely across any realistic number of sync-session messages. */
    fun encrypt(keyBytes: ByteArray, plaintext: ByteArray): ByteArray {
        val iv = ByteArray(IV_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        return iv + cipher.doFinal(plaintext)
    }

    fun decrypt(keyBytes: ByteArray, payload: ByteArray): ByteArray {
        require(payload.size > IV_LENGTH_BYTES) { "Sync payload too short to contain an IV" }
        val iv = payload.copyOfRange(0, IV_LENGTH_BYTES)
        val ciphertext = payload.copyOfRange(IV_LENGTH_BYTES, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        return cipher.doFinal(ciphertext)
    }
}
