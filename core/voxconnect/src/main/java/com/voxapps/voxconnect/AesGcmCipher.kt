package com.voxapps.voxconnect

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM encrypt/decrypt for VoxConnect request/response bodies — every call after pairing is
 * encrypted with the pairing's session key, even on a trusted LAN (defense in depth; the HTTP layer
 * itself is plain, unencrypted transport). A fresh, self-contained implementation — conceptually
 * mirrors vox-hub's `SecureSyncChannel` (same primitives: 256-bit key, 12-byte IV, 128-bit tag) but
 * not a shared extraction, since that class lives in an app module this one can't depend on.
 */
object AesGcmCipher {

    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val IV_LENGTH_BYTES = 12
    private const val TAG_LENGTH_BITS = 128
    const val KEY_LENGTH_BYTES = 32

    fun generateKey(): ByteArray = ByteArray(KEY_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }

    fun keyToBase64(key: ByteArray): String = Base64.getEncoder().encodeToString(key)

    fun keyFromBase64(base64: String): ByteArray = Base64.getDecoder().decode(base64)

    /** Returns base64(IV || ciphertext || tag) — a single self-contained token safe to put in an
     *  HTTP body or QR payload. */
    fun encrypt(key: ByteArray, plaintext: String): String {
        val iv = ByteArray(IV_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + ciphertext)
    }

    /** Inverse of [encrypt]. Returns null on any malformed input or a failed tag check (tampered/wrong
     *  key) rather than throwing — callers treat that as "reject the request", not a crash. */
    fun decrypt(key: ByteArray, token: String): String? = try {
        val raw = Base64.getDecoder().decode(token)
        require(raw.size > IV_LENGTH_BYTES)
        val iv = raw.copyOfRange(0, IV_LENGTH_BYTES)
        val ciphertext = raw.copyOfRange(IV_LENGTH_BYTES, raw.size)
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }
}
