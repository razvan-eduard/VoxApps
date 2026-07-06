package com.voxapps.commander.domain.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for [WakeWordProfile] JSON (de)serialization. Uses real org.json
 * (see the org.json testImplementation in app/build.gradle.kts).
 */
class WakeWordProfileTest {

    @Test
    fun `fromJson parses all fields`() {
        val json = """
            {
              "rmsThreshold": 0.5, "minRms": 0.1, "maxRms": 0.9, "avgRms": 0.4,
              "peakFreqLow": 300.0, "peakFreqHigh": 3000.0,
              "wakeWord": "hi vox", "calibrationDate": 123456,
              "voicePrint": "0.1,0.2", "similarityThreshold": 0.8,
              "wakeWordTemplate": "t", "templateThreshold": 0.5,
              "profileName": "p", "noiseFloorRms": 0.05
            }
        """.trimIndent()

        val p = WakeWordProfile.fromJson(json)!!
        assertEquals(0.5f, p.rmsThreshold, 1e-6f)
        assertEquals(0.9f, p.maxRms, 1e-6f)
        assertEquals("hi vox", p.wakeWord)
        assertEquals(123456L, p.calibrationDate)
        assertEquals("0.1,0.2", p.voicePrint)
        assertEquals(0.8f, p.similarityThreshold, 1e-6f)
        assertEquals("t", p.wakeWordTemplate)
        assertEquals(0.5f, p.templateThreshold, 1e-6f)
        assertEquals("p", p.profileName)
        assertEquals(0.05f, p.noiseFloorRms, 1e-6f)
    }

    @Test
    fun `fromJson applies defaults for absent optional fields`() {
        val json = """
            {
              "rmsThreshold": 0.5, "minRms": 0.1, "maxRms": 0.9, "avgRms": 0.4,
              "peakFreqLow": 300.0, "peakFreqHigh": 3000.0,
              "wakeWord": "hi vox", "calibrationDate": 123456
            }
        """.trimIndent()

        val p = WakeWordProfile.fromJson(json)!!
        assertNull(p.voicePrint)
        assertEquals(0.65f, p.similarityThreshold, 1e-6f)
        assertNull(p.wakeWordTemplate)
        assertEquals(0.45f, p.templateThreshold, 1e-6f)
        assertNull(p.profileName)
        assertEquals(0f, p.noiseFloorRms, 1e-6f)
    }

    @Test
    fun `toJson then fromJson round-trips`() {
        val original = WakeWordProfile(
            rmsThreshold = 0.3f, minRms = 0.05f, maxRms = 0.8f, avgRms = 0.35f,
            peakFreqLow = 320f, peakFreqHigh = 2800f, wakeWord = "computer",
            calibrationDate = 999L, voicePrint = "0.5,0.6", profileName = "me", noiseFloorRms = 0.02f
        )
        val restored = WakeWordProfile.fromJson(WakeWordProfile.toJson(original))!!
        assertEquals(original, restored)
    }

    @Test
    fun `fromJson returns null when a required field is missing`() {
        // no "wakeWord"
        val json = """
            {"rmsThreshold":0.5,"minRms":0.1,"maxRms":0.9,"avgRms":0.4,
             "peakFreqLow":300.0,"peakFreqHigh":3000.0,"calibrationDate":1}
        """.trimIndent()
        assertNull(WakeWordProfile.fromJson(json))
    }

    @Test
    fun `fromJson returns null for malformed json`() {
        assertNull(WakeWordProfile.fromJson("{not json"))
    }
}
