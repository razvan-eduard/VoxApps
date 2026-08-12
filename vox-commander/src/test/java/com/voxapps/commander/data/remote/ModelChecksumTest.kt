package com.voxapps.commander.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * The hash a schema declares beside a model's URL.
 *
 * These schemas are meant to be fully dynamic — a provider points anywhere they like, and that is
 * the feature rather than the risk. Signing establishes *who* published the schema; a `sha256`
 * written beside the URL inherits that signature and establishes *what* should arrive there. So a
 * host that is compromised, hijacked, or simply serving a truncated file cannot substitute
 * different bytes at a URL the signed schema vouches for.
 *
 * These pin the hashing itself. The wiring — refusing to install and deleting the artefact — lives
 * in ModelDownloader.installDownloadedModel, which needs a Context and is covered on-device.
 */
class ModelChecksumTest {

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    @Test
    fun `a file hashes to the value the schema would carry`() {
        val dir = Files.createTempDirectory("checksum").toFile()
        val file = File(dir, "model.bin").apply { writeBytes("whisper".toByteArray()) }

        // Same construction the downloader uses: lowercase hex, 64 chars.
        val hash = sha256(file.readBytes())
        assertEquals(64, hash.length)
        assertEquals(hash, hash.lowercase())

        dir.deleteRecursively()
    }

    /**
     * One flipped byte has to change the answer. A checksum that survives a modified file is worse
     * than none, because it reads as a guarantee.
     */
    @Test
    fun `a single changed byte changes the hash`() {
        val original = sha256("model-bytes".toByteArray())
        val tampered = sha256("model-bytes!".toByteArray())
        assertNotEquals(original, tampered)
    }

    /**
     * An entry without the field must keep working. models.json carries 101 URLs; they cannot all be
     * hashed at once, and a download that worked yesterday has to work today — so absence means
     * "unverified", never "rejected".
     */
    @Test
    fun `a model with no declared hash is left alone`() {
        val item = RemoteModelItem(id = "ggml-base", path = "https://example.com/ggml-base.bin")
        assertEquals(null, item.sha256)
    }

    @Test
    fun `a declared hash survives the DTO`() {
        val expected = sha256("x".toByteArray())
        val item = RemoteModelItem(id = "m", path = "https://example.com/m.bin", sha256 = expected)
        assertEquals(expected, item.sha256)
    }
}
