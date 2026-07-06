package com.voxapps.commander.utils

/**
 * Second-order biquad bandpass filter (Butterworth).
 * Passes frequencies in [lowFreq, highFreq] range, attenuates everything else.
 * Used for voice activity detection — isolates the 300-3400 Hz voice band.
 */
class BandpassFilter(sampleRate: Float, lowFreq: Float, highFreq: Float) {
    private var x1 = 0f; private var x2 = 0f
    private var y1 = 0f; private var y2 = 0f

    private val a1: Float
    private val a2: Float
    private val b0: Float
    private val b1: Float
    private val b2: Float

    init {
        val w1 = 2.0 * Math.PI * lowFreq / sampleRate
        val w2 = 2.0 * Math.PI * highFreq / sampleRate

        val k1 = Math.tan(w1 / 2)
        val k2 = Math.tan(w2 / 2)

        val bw = (k2 - k1).toFloat()
        val center = (k1 * k2).toFloat()

        val norm = 1f + bw + center

        b0 = bw / norm
        b1 = 0f
        b2 = -bw / norm
        a1 = (2f * (center - 1f)) / norm
        a2 = (1f - bw + center) / norm
    }

    fun process(input: ShortArray, output: FloatArray, length: Int) {
        for (i in 0 until length) {
            val x0 = input[i].toFloat() / 32768f
            val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2

            output[i] = y0

            x2 = x1; x1 = x0
            y2 = y1; y1 = y0
        }
    }

    fun reset() {
        x1 = 0f; x2 = 0f
        y1 = 0f; y2 = 0f
    }
}
