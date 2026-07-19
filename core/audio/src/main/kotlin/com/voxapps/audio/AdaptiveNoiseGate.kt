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
 * Deliberately allocation-free per call (primitive `LongArray`/`FloatArray` ring buffers, no boxed
 * `Pair`/`List`) — this runs on every single audio frame (tens of times a second) inside an
 * always-on background service for hours at a stretch, so per-call GC churn is a real, measurable
 * cost, not a rounding error. The ring buffer only allocates on the rare occasion it needs to grow
 * past its initial capacity (sized generously enough that steady-state operation shouldn't).
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
    private var timestamps = LongArray(INITIAL_CAPACITY)
    private var values = FloatArray(INITIAL_CAPACITY)
    private var scratch = FloatArray(INITIAL_CAPACITY) // reused for the percentile sort
    private var head = 0
    private var count = 0

    /** Feeds one frame's RMS energy and its timestamp; returns this frame's effective threshold. */
    fun effectiveThreshold(rms: Float, nowMs: Long): Float {
        push(nowMs, rms)
        evictOlderThan(nowMs - windowMs)
        val floor = percentile(floorPercentile)
        return (floor * marginMultiplier).coerceIn(minThreshold, maxThreshold)
    }

    /** Convenience: true if [rms] should be treated as signal (gate open) at time [nowMs]. */
    fun isSignal(rms: Float, nowMs: Long): Boolean = rms >= effectiveThreshold(rms, nowMs)

    private fun push(nowMs: Long, rms: Float) {
        if (count == timestamps.size) grow()
        val tail = (head + count) % timestamps.size
        timestamps[tail] = nowMs
        values[tail] = rms
        count++
    }

    private fun evictOlderThan(cutoffMs: Long) {
        while (count > 0 && timestamps[head] < cutoffMs) {
            head = (head + 1) % timestamps.size
            count--
        }
    }

    private fun grow() {
        val newCapacity = timestamps.size * 2
        val newTimestamps = LongArray(newCapacity)
        val newValues = FloatArray(newCapacity)
        for (i in 0 until count) {
            val src = (head + i) % timestamps.size
            newTimestamps[i] = timestamps[src]
            newValues[i] = values[src]
        }
        timestamps = newTimestamps
        values = newValues
        scratch = FloatArray(newCapacity)
        head = 0
    }

    private fun percentile(p: Float): Float {
        if (count == 0) return 0f
        for (i in 0 until count) {
            scratch[i] = values[(head + i) % values.size]
        }
        java.util.Arrays.sort(scratch, 0, count)
        val index = (p * (count - 1)).toInt().coerceIn(0, count - 1)
        return scratch[index]
    }

    private companion object {
        // Comfortably covers a 3s window even at an aggressive ~10ms/frame (300 samples) without
        // ever needing to grow in the common case; grow() is a correct fallback for anything faster.
        const val INITIAL_CAPACITY = 256
    }
}
