package com.voxapps.commander.domain.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tests for [VoiceFeatureExtractor] — the 8-band spectral feature/DTW math used for
 * wake-word voice-print and template matching. Pure kotlin.math, fully deterministic.
 */
class VoiceFeatureExtractorTest {

    private val bands = 8

    /** A tone at [freqHz] (16kHz sample rate), amplitude ~10000. */
    private fun tone(freqHz: Double, n: Int): ShortArray =
        ShortArray(n) { i -> (10000.0 * sin(2 * PI * freqHz * i / 16000.0)).toInt().toShort() }

    private fun l2(v: FloatArray): Float = sqrt(v.fold(0.0) { a, x -> a + x * x }).toFloat()

    @Test
    fun `similarity of identical vectors is one, orthogonal is zero`() {
        val a = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        val b = floatArrayOf(0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f)
        assertEquals(1f, VoiceFeatureExtractor.similarity(a, a), 1e-6f)
        assertEquals(0f, VoiceFeatureExtractor.similarity(a, b), 1e-6f)
    }

    @Test
    fun `similarity of mismatched sizes is zero`() {
        assertEquals(0f, VoiceFeatureExtractor.similarity(FloatArray(8), FloatArray(4)), 0f)
    }

    @Test
    fun `average is the renormalized mean`() {
        val a = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        val b = floatArrayOf(0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f)
        val avg = VoiceFeatureExtractor.average(listOf(a, b))
        assertEquals(0.7071f, avg[0], 1e-3f)
        assertEquals(0.7071f, avg[1], 1e-3f)
        assertEquals(1f, l2(avg), 1e-3f)
    }

    @Test
    fun `average of empty list is zero vector`() {
        val avg = VoiceFeatureExtractor.average(emptyList())
        assertEquals(bands, avg.size)
        assertEquals(0f, l2(avg), 0f)
    }

    @Test
    fun `extract is deterministic and unit-normalized for a tone`() {
        val samples = tone(1000.0, 1600)
        val f1 = VoiceFeatureExtractor.extract(samples, 1600)
        val f2 = VoiceFeatureExtractor.extract(samples, 1600)
        assertEquals(bands, f1.size)
        assertEquals(f1.toList(), f2.toList())
        assertEquals(1f, l2(f1), 1e-3f)
    }

    @Test
    fun `extract of silence is all zeros`() {
        val f = VoiceFeatureExtractor.extract(ShortArray(1600), 1600)
        assertEquals(0f, l2(f), 0f)
    }

    @Test
    fun `extractSequence produces frames of band-sized vectors`() {
        val seq = VoiceFeatureExtractor.extractSequence(tone(1000.0, 1600), 1600)
        // (1600-400)/160 + 1 = 8 frames
        assertEquals(8, seq.size)
        assertTrue(seq.all { it.size == bands })
    }

    @Test
    fun `sequenceSimilarity of identical sequences is one`() {
        val seq = VoiceFeatureExtractor.extractSequence(tone(1000.0, 1600), 1600)
        assertEquals(1f, VoiceFeatureExtractor.sequenceSimilarity(seq, seq), 1e-3f)
    }

    @Test
    fun `sequenceSimilarity with empty input is zero`() {
        val seq = VoiceFeatureExtractor.extractSequence(tone(1000.0, 1600), 1600)
        assertEquals(0f, VoiceFeatureExtractor.sequenceSimilarity(emptyArray(), seq), 0f)
    }

    @Test
    fun `vector encode-decode round trips`() {
        val v = floatArrayOf(0.1f, 0.25f, 0f, 0.5f, 0.5f, 0f, 0f, 0.3f)
        val decoded = VoiceFeatureExtractor.decodeVector(VoiceFeatureExtractor.encodeVector(v))!!
        assertEquals(v.toList(), decoded.toList())
        assertNull(VoiceFeatureExtractor.decodeVector(null))
        assertNull(VoiceFeatureExtractor.decodeVector(""))
    }

    @Test
    fun `sequence encode-decode round trips`() {
        val seq = arrayOf(floatArrayOf(0.1f, 0.2f), floatArrayOf(0.3f, 0.4f))
        val decoded = VoiceFeatureExtractor.decodeSequence(VoiceFeatureExtractor.encodeSequence(seq))!!
        assertEquals(2, decoded.size)
        assertEquals(seq[0].toList(), decoded[0].toList())
        assertEquals(seq[1].toList(), decoded[1].toList())
    }

    @Test
    fun `averageSequences returns null for empty and identity for single`() {
        assertNull(VoiceFeatureExtractor.averageSequences(emptyList()))
        val seq = arrayOf(floatArrayOf(1f, 2f))
        assertEquals(seq, VoiceFeatureExtractor.averageSequences(listOf(seq)))
    }
}
