package com.voxapps.hub.domain.sync

import android.bluetooth.BluetoothSocket
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Length-prefixed, AES-GCM-encrypted (see [SyncCrypto]) message framing over a raw [BluetoothSocket]
 * stream — RFCOMM gives us bytes-in/bytes-out with no message boundaries, so every [send]/[receive]
 * wraps one JSON string as `[4-byte big-endian length][IV + ciphertext + tag]`.
 */
class SecureSyncChannel(
    private val socket: BluetoothSocket,
    private val keyBytes: ByteArray
) : Closeable {
    private val input = DataInputStream(socket.inputStream)
    private val output = DataOutputStream(socket.outputStream)

    fun send(plaintext: String) {
        val encrypted = SyncCrypto.encrypt(keyBytes, plaintext.toByteArray(Charsets.UTF_8))
        output.writeInt(encrypted.size)
        output.write(encrypted)
        output.flush()
    }

    fun receive(): String {
        val length = input.readInt()
        require(length in 0..MAX_MESSAGE_BYTES) { "Sync message size out of bounds: $length" }
        val encrypted = ByteArray(length)
        input.readFully(encrypted)
        return String(SyncCrypto.decrypt(keyBytes, encrypted), Charsets.UTF_8)
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        /** A generous ceiling (well beyond any realistic per-app delta) purely to reject a
         *  corrupted/adversarial length prefix before allocating a buffer for it. */
        private const val MAX_MESSAGE_BYTES = 32 * 1024 * 1024
    }
}
