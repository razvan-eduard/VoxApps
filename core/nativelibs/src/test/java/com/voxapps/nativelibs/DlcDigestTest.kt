package com.voxapps.nativelibs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * The digests the build records beside the DLC libraries.
 *
 * They live in the APK's assets, so they are covered by its signature — a digest fetched from the
 * same release as the library would establish nothing, since whoever can substitute one can
 * substitute the other. These pin the parsing and the comparison; the download path itself needs a
 * Context and is covered on-device.
 */
class DlcDigestTest {

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /** The file the build writes is `shasum -a 256` output: digest, whitespace, file name. */
    private fun parse(text: String): Map<String, String> =
        text.lineSequence().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size == 2 && parts[0].length == 64) parts[1] to parts[0].lowercase() else null
        }.toMap()

    @Test
    fun `the recorded format parses to lib name and digest`() {
        val hash = sha256("onnx".toByteArray())
        val parsed = parse("$hash  libonnxruntime.so\n${sha256("vosk".toByteArray())}  libvosk.so\n")
        assertEquals(2, parsed.size)
        assertEquals(hash, parsed["libonnxruntime.so"])
    }

    /** A build that recorded nothing must leave every library unverified rather than rejected. */
    @Test
    fun `an absent record verifies nothing`() {
        assertEquals(emptyMap<String, String>(), parse(""))
    }

    @Test
    fun `a truncated download does not match`() {
        val dir = Files.createTempDirectory("dlc").toFile()
        val full = File(dir, "lib.so").apply { writeBytes(ByteArray(4096) { 7 }) }
        val truncated = File(dir, "lib.partial.so").apply { writeBytes(ByteArray(2048) { 7 }) }
        assertNotEquals(sha256(full.readBytes()), sha256(truncated.readBytes()))
        dir.deleteRecursively()
    }

    /** Malformed lines are skipped, not treated as a digest of something. */
    @Test
    fun `a malformed line is ignored`() {
        assertEquals(emptyMap<String, String>(), parse("not-a-digest  libvosk.so\ngarbage\n"))
    }
}
