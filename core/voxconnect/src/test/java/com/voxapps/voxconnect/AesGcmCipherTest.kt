package com.voxapps.voxconnect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AesGcmCipherTest {

    @Test
    fun `encrypt then decrypt with the same key recovers the original plaintext`() {
        val key = AesGcmCipher.generateKey()
        val plaintext = """{"op":"read","domain":"notes"}"""

        val token = AesGcmCipher.encrypt(key, plaintext)
        val decrypted = AesGcmCipher.decrypt(key, token)

        assertEquals(plaintext, decrypted)
    }

    @Test
    fun `two encryptions of the same plaintext produce different tokens`() {
        val key = AesGcmCipher.generateKey()
        val plaintext = "hello"

        val first = AesGcmCipher.encrypt(key, plaintext)
        val second = AesGcmCipher.encrypt(key, plaintext)

        // Different random IV each call — same plaintext must not produce the same ciphertext,
        // otherwise repeated identical requests would be distinguishable on the wire.
        assertNotEquals(first, second)
    }

    @Test
    fun `decrypting with the wrong key fails closed instead of throwing`() {
        val key = AesGcmCipher.generateKey()
        val wrongKey = AesGcmCipher.generateKey()
        val token = AesGcmCipher.encrypt(key, "secret")

        assertNull(AesGcmCipher.decrypt(wrongKey, token))
    }

    @Test
    fun `decrypting malformed input returns null instead of throwing`() {
        val key = AesGcmCipher.generateKey()

        assertNull(AesGcmCipher.decrypt(key, "not-valid-base64!!!"))
        assertNull(AesGcmCipher.decrypt(key, ""))
    }

    @Test
    fun `key round-trips through base64 encoding`() {
        val key = AesGcmCipher.generateKey()

        val restored = AesGcmCipher.keyFromBase64(AesGcmCipher.keyToBase64(key))

        assertEquals(key.toList(), restored.toList())
    }
}
