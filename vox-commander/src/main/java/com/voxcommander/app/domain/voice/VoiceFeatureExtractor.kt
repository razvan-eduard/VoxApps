package com.voxcommander.app.domain.voice

import kotlin.math.sqrt
import kotlin.math.min
import kotlin.math.max

/**
 * Extracts a voice print (8-band spectral energy vector) from audio.
 * Used for voice verification — comparing live audio against calibrated samples.
 *
 * 8 sub-bands covering the voice range (300-3400 Hz):
 *   300-450, 450-650, 650-900, 900-1200, 1200-1550, 1550-2000, 2000-2600, 2600-3400
 */
object VoiceFeatureExtractor {

    private val BANDS = listOf(
        300f to 450f,
        450f to 650f,
        650f to 900f,
        900f to 1200f,
        1200f to 1550f,
        1550f to 2000f,
        2000f to 2600f,
        2600f to 3400f
    )

    data class BandFilter(
        val a1: Float, val a2: Float,
        val b0: Float, val b1: Float, val b2: Float
    )

    private fun createBandFilter(sampleRate: Float, lowFreq: Float, highFreq: Float): BandFilter {
        val w1 = 2.0 * Math.PI * lowFreq / sampleRate
        val w2 = 2.0 * Math.PI * highFreq / sampleRate
        val k1 = Math.tan(w1 / 2)
        val k2 = Math.tan(w2 / 2)
        val bw = (k2 - k1).toFloat()
        val center = (k1 * k2).toFloat()
        val norm = 1f + bw + center
        return BandFilter(
            a1 = (2f * (center - 1f)) / norm,
            a2 = (1f - bw + center) / norm,
            b0 = bw / norm,
            b1 = 0f,
            b2 = -bw / norm
        )
    }

    private val bandFilters = BANDS.map { (low, high) ->
        createBandFilter(16000f, low, high)
    }

    /**
     * Extract an 8-element normalized spectral energy vector from audio samples.
     * Each element is the RMS energy in one sub-band, normalized so the vector magnitude = 1.
     */
    fun extract(samples: ShortArray, length: Int): FloatArray {
        val features = FloatArray(BANDS.size)

        for (bandIdx in bandFilters.indices) {
            val filter = bandFilters[bandIdx]
            var x1 = 0f; var x2 = 0f
            var y1 = 0f; var y2 = 0f
            var sumSq = 0.0

            for (i in 0 until length) {
                val x0 = samples[i].toFloat() / 32768f
                val y0 = filter.b0 * x0 + filter.b1 * x1 + filter.b2 * x2 - filter.a1 * y1 - filter.a2 * y2
                sumSq += y0.toDouble() * y0
                x2 = x1; x1 = x0
                y2 = y1; y1 = y0
            }

            features[bandIdx] = sqrt(sumSq / length.coerceAtLeast(1)).toFloat()
        }

        // Normalize: divide by L2 norm so vector magnitude = 1
        val norm = sqrt(features.foldIndexed(0.0) { _, acc, v -> acc + v * v }).toFloat()
        if (norm > 0f) {
            for (i in features.indices) features[i] /= norm
        }

        return features
    }

    /**
     * Cosine similarity between two feature vectors.
     * Since vectors are already normalized, this is just the dot product.
     * Returns value in [0, 1] where 1 = identical spectral shape.
     */
    fun similarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot.coerceIn(0f, 1f)
    }

    /**
     * Average multiple feature vectors into a single template.
     */
    fun average(vectors: List<FloatArray>): FloatArray {
        if (vectors.isEmpty()) return FloatArray(BANDS.size)
        val result = FloatArray(BANDS.size)
        for (vec in vectors) {
            for (i in vec.indices) result[i] += vec[i]
        }
        for (i in result.indices) result[i] /= vectors.size

        // Re-normalize
        val norm = sqrt(result.foldIndexed(0.0) { _, acc, v -> acc + v * v }).toFloat()
        if (norm > 0f) {
            for (i in result.indices) result[i] /= norm
        }
        return result
    }

    // --- Sequence extraction for template matching (language-agnostic KWS) ---

    private const val FRAME_SIZE_MS = 25f
    private const val HOP_SIZE_MS = 10f
    private const val SAMPLE_RATE = 16000

    /**
     * Extract a sequence of 8-band feature vectors from audio (frame-by-frame).
     * Each frame is 25ms with 10ms hop. Returns an array of normalized vectors.
     * This captures the temporal evolution of the word — not just the speaker's voice.
     */
    fun extractSequence(samples: ShortArray, length: Int): Array<FloatArray> {
        val frameSize = (FRAME_SIZE_MS * SAMPLE_RATE / 1000).toInt() // 400 samples
        val hopSize = (HOP_SIZE_MS * SAMPLE_RATE / 1000).toInt()     // 160 samples
        val numFrames = max(1, (length - frameSize) / hopSize + 1)

        val sequence = Array(numFrames) { FloatArray(BANDS.size) }

        for (frameIdx in 0 until numFrames) {
            val start = frameIdx * hopSize
            val end = min(start + frameSize, length)
            val frameLen = end - start
            if (frameLen <= 0) break

            val frame = samples.copyOfRange(start, end)
            sequence[frameIdx] = extract(frame, frameLen)
        }

        return sequence
    }

    /**
     * Dynamic Time Warping distance between two feature sequences.
     * Handles different speaking speeds by finding optimal alignment.
     * Returns a distance value (lower = more similar).
     */
    private fun dtwDistance(a: Array<FloatArray>, b: Array<FloatArray>): Float {
        val n = a.size
        val m = b.size
        if (n == 0 || m == 0) return Float.MAX_VALUE

        // Use a band constraint to limit DTW path (Sakoe-Chiba band)
        val band = max(n, m) / 3

        val dp = Array(n + 1) { FloatArray(m + 1) { Float.MAX_VALUE } }
        dp[0][0] = 0f

        for (i in 1..n) {
            val jStart = max(1, i - band)
            val jEnd = min(m, i + band)
            for (j in jStart..jEnd) {
                val cost = euclideanDistance(a[i - 1], b[j - 1])
                val minPrev = min(dp[i - 1][j], min(dp[i][j - 1], dp[i - 1][j - 1]))
                dp[i][j] = if (minPrev == Float.MAX_VALUE) Float.MAX_VALUE else cost + minPrev
            }
        }

        return dp[n][m]
    }

    private fun euclideanDistance(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) {
            val d = a[i] - b[i]
            sum += d * d
        }
        return sqrt(sum)
    }

    /**
     * Compare a live audio sequence against a stored template.
     * Returns similarity score in [0, 1] where 1 = perfect match.
     * Uses DTW distance converted to similarity via exponential decay.
     */
    fun sequenceSimilarity(template: Array<FloatArray>, live: Array<FloatArray>): Float {
        if (template.isEmpty() || live.isEmpty()) return 0f

        val dist = dtwDistance(template, live)
        if (dist == Float.MAX_VALUE) return 0f

        // Normalize by path length and convert to similarity
        val normDist = dist / (template.size + live.size)
        // Exponential decay: similarity = exp(-alpha * normDist)
        // alpha tuned so that 0.5 threshold ≈ moderate match
        val alpha = 8f
        val sim = kotlin.math.exp(-alpha * normDist.toDouble()).toFloat()
        return sim.coerceIn(0f, 1f)
    }

    /**
     * Average multiple template sequences into a single reference template.
     * Aligns all sequences to the median length using DTW path, then averages.
     * For simplicity, we just pick the medoid (sequence with lowest total DTW to all others).
     */
    fun averageSequences(sequences: List<Array<FloatArray>>): Array<FloatArray>? {
        if (sequences.isEmpty()) return null
        if (sequences.size == 1) return sequences[0]

        // Find medoid: the sequence with lowest total DTW distance to all others
        var bestIdx = 0
        var bestTotalDist = Float.MAX_VALUE

        for (i in sequences.indices) {
            var totalDist = 0f
            for (j in sequences.indices) {
                if (i == j) continue
                totalDist += dtwDistance(sequences[i], sequences[j])
            }
            if (totalDist < bestTotalDist) {
                bestTotalDist = totalDist
                bestIdx = i
            }
        }

        return sequences[bestIdx]
    }

    /**
     * Encode a sequence of feature vectors as a string for JSON storage.
     * Format: "frame0band0,frame0band1,...;frame1band0,...;..."
     */
    fun encodeSequence(seq: Array<FloatArray>): String {
        return seq.joinToString(";") { frame ->
            frame.joinToString(",") { it.toString() }
        }
    }

    /**
     * Decode a sequence from string format.
     */
    fun decodeSequence(s: String?): Array<FloatArray>? {
        if (s.isNullOrBlank()) return null
        return try {
            val frames = s.split(";")
            Array(frames.size) { i ->
                val bands = frames[i].split(",")
                FloatArray(bands.size) { bands[it].toFloat() }
            }
        } catch (e: Exception) {
            null
        }
    }

    // --- Single vector encoding (for voice print) ---

    /**
     * Convert a FloatArray to a JSON-serializable string.
     */
    fun encodeVector(vec: FloatArray): String {
        return vec.joinToString(",") { it.toString() }
    }

    /**
     * Parse a FloatArray from the encoded string.
     */
    fun decodeVector(s: String?): FloatArray? {
        if (s.isNullOrBlank()) return null
        return try {
            val parts = s.split(",")
            FloatArray(parts.size) { parts[it].toFloat() }
        } catch (e: Exception) {
            null
        }
    }
}
