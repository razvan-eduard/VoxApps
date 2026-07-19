package com.voxapps.audio

/**
 * Gates expensive per-frame wake-word work (ONNX inference, ASR decode) behind a threshold that
 * tracks the *current* ambient noise floor instead of a fixed constant. A fixed threshold works
 * fine in a quiet room (ambient RMS sits below it, so the gate closes and the expensive work is
 * skipped) but stops helping at all in a sustained noisy environment — traffic, a TV, a fan —
 * where ambient RMS sits above the fixed floor continuously, so the gate never closes and the
 * engine ends up running at full rate regardless of the gate's presence.
 *
 * Engine-agnostic and frame-duration-agnostic: callers (OpenWakeWord's fixed 80ms buffers, Vosk's
 * device-dependent `AudioRecord` buffer size) just feed `(rms, timestamp)` pairs; the rolling
 * window is time-based, not frame-count-based, so it means the same thing regardless of how often
 * the caller calls in.
 *
 * @param minThreshold Calibrated/default floor — the effective threshold never drops below this,
 *   preserving each engine's existing quiet-room behavior.
 * @param marginMultiplier How far above the tracked noise floor the effective threshold sits.
 *   Derived from the user's wake-word sensitivity — see the consuming app's
 *   `WakeWordSensitivity.noiseGateMargin()`.
 * @param maxThreshold Ceiling clamp — prevents the threshold from climbing so high in sustained
 *   loud noise that genuine speech at conversational volume can no longer pass it at all.
 * @param windowMs How far back the rolling noise-floor estimate looks.
 * @param floorPercentile Which percentile of the windowed samples counts as "the floor" — a low
 *   percentile (not a mean) is deliberate: an average gets dragged up by occasional loud
 *   transients (a cough, a door), a low percentile mostly ignores them and only rises when the
 *   ambient level is *sustained*.
 */
class AdaptiveNoiseGate(
    private val minThreshold: Float,
    private val marginMultiplier: Float,
    private val maxThreshold: Float = minThreshold * 4f,
    private val windowMs: Long = 3000L,
    private val floorPercentile: Float = 0.2f
) {
    private val samples = ArrayDeque<Pair<Long, Float>>()

    /** Feeds one frame's RMS energy and its timestamp; returns this frame's effective threshold. */
    fun effectiveThreshold(rms: Float, nowMs: Long): Float {
        samples.addLast(nowMs to rms)
        while (samples.isNotEmpty() && nowMs - samples.first().first > windowMs) {
            samples.removeFirst()
        }
        val floor = percentile(samples, floorPercentile)
        return (floor * marginMultiplier).coerceIn(minThreshold, maxThreshold)
    }

    /** Convenience: true if [rms] should be treated as signal (gate open) at time [nowMs]. */
    fun isSignal(rms: Float, nowMs: Long): Boolean = rms >= effectiveThreshold(rms, nowMs)

    private fun percentile(values: ArrayDeque<Pair<Long, Float>>, p: Float): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.map { it.second }.sorted()
        val index = (p * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }
}
