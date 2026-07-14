package com.voxapps.commander.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordSensitivityTest {

    @Test
    fun `openWakeWord threshold - higher sensitivity means lower threshold`() {
        val high = WakeWordSensitivity.openWakeWordThreshold("high")
        val medium = WakeWordSensitivity.openWakeWordThreshold("medium")
        val low = WakeWordSensitivity.openWakeWordThreshold("low")
        assertEquals(0.25f, high)
        assertEquals(0.5f, medium)
        assertEquals(0.7f, low)
        // lower threshold = easier trigger = more sensitive
        assertTrue("high must be easier to trigger than low", high < low)
    }

    @Test
    fun `porcupine sensitivity - higher sensitivity means higher value`() {
        val high = WakeWordSensitivity.porcupineSensitivity("high")
        val medium = WakeWordSensitivity.porcupineSensitivity("medium")
        val low = WakeWordSensitivity.porcupineSensitivity("low")
        assertEquals(0.7f, high)
        assertEquals(0.5f, medium)
        assertEquals(0.3f, low)
        // Porcupine is inverted vs the distance-threshold engines: higher = more sensitive
        assertTrue("high must be more sensitive than low", high > low)
        // all within Porcupine's valid 0..1 range
        assertTrue(low in 0f..1f && high in 0f..1f)
    }

    @Test
    fun `vosk template threshold matches the historical mapping`() {
        assertEquals(0.35f, WakeWordSensitivity.voskTemplateThreshold("high"))
        assertEquals(0.45f, WakeWordSensitivity.voskTemplateThreshold("medium"))
        assertEquals(0.55f, WakeWordSensitivity.voskTemplateThreshold("low"))
    }

    @Test
    fun `unknown or null setting falls back to medium for every engine`() {
        for (bad in listOf(null, "", "MEDIUM", "garbage")) {
            assertEquals(0.5f, WakeWordSensitivity.openWakeWordThreshold(bad))
            assertEquals(0.5f, WakeWordSensitivity.porcupineSensitivity(bad))
            assertEquals(0.45f, WakeWordSensitivity.voskTemplateThreshold(bad))
            assertEquals(0.025f, WakeWordSensitivity.openWakeWordRmsGate(bad))
        }
    }

    @Test
    fun `openWakeWord RMS gate - higher sensitivity means a lower (less aggressive) floor`() {
        val high = WakeWordSensitivity.openWakeWordRmsGate("high")
        val medium = WakeWordSensitivity.openWakeWordRmsGate("medium")
        val low = WakeWordSensitivity.openWakeWordRmsGate("low")
        assertEquals(0.01f, high)
        assertEquals(0.025f, medium)
        assertEquals(0.04f, low)
        // high sensitivity must gate less aggressively than low, so quiet speech still reaches ONNX
        assertTrue("high gate must be lower (less aggressive) than low", high < low)
    }
}
