package com.voxcommander.app.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [AudioConvert] — PCM/float conversions and RMS math on the hot STT path.
 */
class AudioConvertTest {

    /** Builds a little-endian PCM16 byte array from short values. */
    private fun leBytes(vararg shorts: Int): ByteArray {
        val out = ByteArray(shorts.size * 2)
        for (i in shorts.indices) {
            out[i * 2] = (shorts[i] and 0xFF).toByte()
            out[i * 2 + 1] = ((shorts[i] shr 8) and 0xFF).toByte()
        }
        return out
    }

    @Test
    fun `byteArrayToShorts decodes little-endian`() {
        val shorts = AudioConvert.byteArrayToShorts(leBytes(1, 256, -32768, 32767))
        assertEquals(shortArrayOf(1, 256, -32768, 32767).toList(), shorts.toList())
    }

    @Test
    fun `byteArrayToShorts truncates odd trailing byte`() {
        // 3 bytes -> 1 short (size / 2)
        val shorts = AudioConvert.byteArrayToShorts(ByteArray(3))
        assertEquals(1, shorts.size)
    }

    @Test
    fun `pcm16ToFloat normalizes by 32768`() {
        val floats = AudioConvert.pcm16ToFloat(leBytes(0, 16384, -32768))
        assertEquals(0f, floats[0], 1e-7f)
        assertEquals(0.5f, floats[1], 1e-7f)
        assertEquals(-1.0f, floats[2], 1e-7f)
    }

    @Test
    fun `calculateRms of silence is zero`() {
        assertEquals(0f, AudioConvert.calculateRms(ShortArray(100), 100), 1e-7f)
    }

    @Test
    fun `calculateRms of constant amplitude`() {
        val buf = ShortArray(64) { 16384 }
        // sqrt(mean(16384^2)) / 32768 = 16384/32768 = 0.5
        assertEquals(0.5f, AudioConvert.calculateRms(buf, 64), 1e-4f)
    }

    @Test
    fun `calculateRms near full scale`() {
        val buf = ShortArray(32) { 32767 }
        assertEquals(0.99997f, AudioConvert.calculateRms(buf, 32), 1e-3f)
    }

    @Test
    fun `calculateFilteredRms of constant floats`() {
        val f = FloatArray(10) { 0.5f }
        assertEquals(0.5f, AudioConvert.calculateFilteredRms(f, 10), 1e-6f)
        assertEquals(0f, AudioConvert.calculateFilteredRms(FloatArray(10), 10), 1e-7f)
    }
}
