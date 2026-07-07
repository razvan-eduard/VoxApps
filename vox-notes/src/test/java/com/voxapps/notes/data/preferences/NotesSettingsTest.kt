package com.voxapps.notes.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NotesSettingsTest {

    @Test
    fun `defaults are open with 30 minute timeout`() {
        val s = NotesSettings()
        assertFalse(s.isBiometricRequired)
        assertEquals(NotesSettings.TIMEOUT_30M, s.sessionTimeoutMinutes)
    }

    @Test
    fun `timeout constants map to expected minutes`() {
        assertEquals(30, NotesSettings.TIMEOUT_30M)
        assertEquals(60, NotesSettings.TIMEOUT_1H)
        assertEquals(1440, NotesSettings.TIMEOUT_1D)
        assertEquals(-1, NotesSettings.TIMEOUT_UNLIMITED)
    }

    @Test
    fun `copy toggles fields independently`() {
        val s = NotesSettings().copy(isBiometricRequired = true, sessionTimeoutMinutes = NotesSettings.TIMEOUT_1D)
        assertEquals(true, s.isBiometricRequired)
        assertEquals(1440, s.sessionTimeoutMinutes)
    }
}
