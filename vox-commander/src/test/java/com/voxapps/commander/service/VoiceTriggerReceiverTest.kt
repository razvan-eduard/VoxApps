package com.voxapps.commander.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for VoiceTriggerReceiver constants and action validation.
 *
 * Full integration tests (onReceive with service start) require instrumented
 * tests on device due to SettingsRepositoryImpl needing DataStore + EncryptedSharedPreferences.
 */
class VoiceTriggerReceiverTest {

    @Test
    fun `ACTION_TRIGGER_VOICE constant is correct`() {
        assertEquals("com.voxapps.commander.TRIGGER_VOICE", VoiceTriggerReceiver.ACTION_TRIGGER_VOICE)
    }

    @Test
    fun `ACTION_TRIGGER_VOICE is not empty`() {
        assertTrue(VoiceTriggerReceiver.ACTION_TRIGGER_VOICE.isNotEmpty())
    }

    @Test
    fun `ACTION_TRIGGER_VOICE follows package naming convention`() {
        assertTrue(VoiceTriggerReceiver.ACTION_TRIGGER_VOICE.startsWith("com.voxapps.commander."))
    }

    @Test
    fun `wrong action does not match trigger action`() {
        val wrongAction = "com.some.other.action"
        assertTrue(wrongAction != VoiceTriggerReceiver.ACTION_TRIGGER_VOICE)
    }

    @Test
    fun `WakeWordService ACTION_EXTERNAL_TRIGGER is correct`() {
        assertEquals("com.voxapps.commander.EXTERNAL_TRIGGER", WakeWordService.ACTION_EXTERNAL_TRIGGER)
    }

    @Test
    fun `ACTION_EXTERNAL_TRIGGER differs from ACTION_START`() {
        assertTrue(WakeWordService.ACTION_EXTERNAL_TRIGGER != WakeWordService.ACTION_START)
    }

    @Test
    fun `ACTION_EXTERNAL_TRIGGER differs from ACTION_STOP`() {
        assertTrue(WakeWordService.ACTION_EXTERNAL_TRIGGER != WakeWordService.ACTION_STOP)
    }
}
