package com.voxapps.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveNoiseGateTest {

    @Test
    fun `quiet room stays near minThreshold`() {
        val gate = AdaptiveNoiseGate(minThreshold = 0.01f, marginMultiplier = 2.0f)
        var t = 0L
        repeat(20) {
            gate.effectiveThreshold(rms = 0.001f, nowMs = t)
            t += 80
        }
        val threshold = gate.effectiveThreshold(rms = 0.001f, nowMs = t)
        assertEquals(0.01f, threshold, 0.0001f)
    }

    @Test
    fun `an occasional loud transient does not drag the floor up`() {
        val gate = AdaptiveNoiseGate(minThreshold = 0.01f, marginMultiplier = 2.0f)
        var t = 0L
        repeat(20) {
            gate.effectiveThreshold(rms = 0.001f, nowMs = t)
            t += 80
        }
        // one loud spike (a door, a cough)
        gate.effectiveThreshold(rms = 0.5f, nowMs = t)
        t += 80
        val threshold = gate.effectiveThreshold(rms = 0.001f, nowMs = t)
        assertEquals(0.01f, threshold, 0.0001f)
    }

    @Test
    fun `sustained elevated noise raises the threshold proportionally`() {
        val gate = AdaptiveNoiseGate(minThreshold = 0.01f, marginMultiplier = 2.0f, maxThreshold = 1f)
        var t = 0L
        var threshold = 0f
        repeat(60) {
            threshold = gate.effectiveThreshold(rms = 0.05f, nowMs = t)
            t += 80
        }
        assertTrue("expected threshold to rise above minThreshold, was $threshold", threshold > 0.01f)
        assertEquals(0.1f, threshold, 0.01f) // ~0.05 floor * 2.0 margin
    }

    @Test
    fun `threshold is clamped at maxThreshold under extreme sustained noise`() {
        val gate = AdaptiveNoiseGate(minThreshold = 0.01f, marginMultiplier = 2.0f, maxThreshold = 0.05f)
        var t = 0L
        var threshold = 0f
        repeat(60) {
            threshold = gate.effectiveThreshold(rms = 0.5f, nowMs = t)
            t += 80
        }
        assertEquals(0.05f, threshold, 0.0001f)
    }

    @Test
    fun `margin multiplier scales the threshold`() {
        val lowMargin = AdaptiveNoiseGate(minThreshold = 0.0f, marginMultiplier = 1.5f, maxThreshold = 1f)
        val highMargin = AdaptiveNoiseGate(minThreshold = 0.0f, marginMultiplier = 3.0f, maxThreshold = 1f)
        var t = 0L
        var lowThreshold = 0f
        var highThreshold = 0f
        repeat(60) {
            lowThreshold = lowMargin.effectiveThreshold(rms = 0.1f, nowMs = t)
            highThreshold = highMargin.effectiveThreshold(rms = 0.1f, nowMs = t)
            t += 80
        }
        assertTrue("expected highMargin ($highThreshold) > lowMargin ($lowThreshold)", highThreshold > lowThreshold)
    }

    @Test
    fun `old samples fall out of the window and stop influencing the estimate`() {
        val gate = AdaptiveNoiseGate(minThreshold = 0.01f, marginMultiplier = 2.0f, windowMs = 1000L, maxThreshold = 1f)
        var t = 0L
        // Loud burst, fills the whole window
        repeat(15) {
            gate.effectiveThreshold(rms = 0.5f, nowMs = t)
            t += 80
        }
        // Now sustained quiet for well past the window duration
        t += 2000L
        var threshold = 0f
        repeat(15) {
            threshold = gate.effectiveThreshold(rms = 0.001f, nowMs = t)
            t += 80
        }
        assertEquals(0.01f, threshold, 0.0001f)
    }

    @Test
    fun `isSignal reflects the effective threshold`() {
        val gate = AdaptiveNoiseGate(minThreshold = 0.01f, marginMultiplier = 2.0f)
        assertFalse(gate.isSignal(rms = 0.001f, nowMs = 0L))
        assertTrue(gate.isSignal(rms = 0.05f, nowMs = 80L))
    }

    @Test
    fun `ring buffer growth beyond initial capacity does not corrupt the estimate`() {
        // A short frame interval over the window forces the internal ring buffer to grow past its
        // initial capacity (256) — this must not lose or corrupt samples relative to a normal run.
        val gate = AdaptiveNoiseGate(minThreshold = 0.01f, marginMultiplier = 2.0f, windowMs = 3000L, maxThreshold = 1f)
        var t = 0L
        var threshold = 0f
        repeat(500) { // 500 frames at 5ms apart = 2500ms of coverage, well past the 256-sample capacity
            threshold = gate.effectiveThreshold(rms = 0.05f, nowMs = t)
            t += 5
        }
        assertEquals(0.1f, threshold, 0.01f) // ~0.05 floor * 2.0 margin, same as the non-grown case
    }

    @Test
    fun `old samples still evict correctly after ring buffer growth`() {
        val gate = AdaptiveNoiseGate(minThreshold = 0.01f, marginMultiplier = 2.0f, windowMs = 1000L, maxThreshold = 1f)
        var t = 0L
        repeat(400) { // forces growth well before the window is even full
            gate.effectiveThreshold(rms = 0.5f, nowMs = t)
            t += 5
        }
        t += 2000L // well past the window
        var threshold = 0f
        repeat(50) {
            threshold = gate.effectiveThreshold(rms = 0.001f, nowMs = t)
            t += 5
        }
        assertEquals(0.01f, threshold, 0.0001f)
    }
}
