package com.voxapps.voxconnect

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The one AES-256-GCM implementation every Vox transport uses: VoxConnect request/response bodies
 * here as base64 tokens, and vox-hub's Bluetooth sync frames through the byte-level pair — same
 * primitives either way (256-bit key, 12-byte IV, 128-bit tag, IV || ciphertext || tag).
 */
object AesGcmCipher {

    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val IV_LENGTH_BYTES = 12
    private const val TAG_LENGTH_BITS = 128
    const val KEY_LENGTH_BYTES = 32

    fun generateKey(): ByteArray = ByteArray(KEY_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }

    fun keyToBase64(key: ByteArray): String = Base64.getEncoder().encodeToString(key)

    fun keyFromBase64(base64: String): ByteArray = Base64.getDecoder().decode(base64)

    /** Prefixes a fresh random IV to the ciphertext+tag so [decryptBytes] is self-contained per
     *  message — a GCM key must never reuse an IV, and a random 96-bit IV makes a collision
     *  astronomically unlikely across any realistic number of messages. */
    fun encryptBytes(key: ByteArray, plaintext: ByteArray): ByteArray {
        val iv = ByteArray(IV_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        return iv + cipher.doFinal(plaintext)
    }

    /** Inverse of [encryptBytes]. Throws on malformed input or a failed tag check — the byte-level
     *  callers (hub's sync channel) treat that as a broken session. */
    fun decryptBytes(key: ByteArray, payload: ByteArray): ByteArray {
        require(payload.size > IV_LENGTH_BYTES) { "Payload too short to contain an IV" }
        val iv = payload.copyOfRange(0, IV_LENGTH_BYTES)
        val ciphertext = payload.copyOfRange(IV_LENGTH_BYTES, payload.size)
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    /** Returns base64(IV || ciphertext || tag) — a single self-contained token safe to put in an
     *  HTTP body or QR payload. */
    fun encrypt(key: ByteArray, plaintext: String): String =
        Base64.getEncoder().encodeToString(encryptBytes(key, plaintext.toByteArray(Charsets.UTF_8)))

    /** Inverse of [encrypt]. Returns null on any malformed input or a failed tag check (tampered/wrong
     *  key) rather than throwing — callers treat that as "reject the request", not a crash. */
    fun decrypt(key: ByteArray, token: String): String? = try {
        String(decryptBytes(key, Base64.getDecoder().decode(token)), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }
}
