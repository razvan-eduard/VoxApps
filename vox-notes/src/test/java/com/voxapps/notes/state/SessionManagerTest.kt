package com.voxapps.notes.state

import com.voxapps.notes.data.preferences.NotesSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionManagerTest {

    private var now = 1_000_000L
    private val sm = SessionManager(clock = { now })

    @Test
    fun `never unlocked is invalid`() {
        assertFalse(sm.isSessionValid(NotesSettings.TIMEOUT_30M))
    }

    @Test
    fun `valid within window, invalid after`() {
        sm.markUnlocked()
        now += 29 * 60_000L
        assertTrue(sm.isSessionValid(NotesSettings.TIMEOUT_30M))
        now += 2 * 60_000L // 31 min total
        assertFalse(sm.isSessionValid(NotesSettings.TIMEOUT_30M))
    }

    @Test
    fun `unlimited never expires`() {
        sm.markUnlocked()
        now += 100L * 24 * 60 * 60_000L // 100 days
        assertTrue(sm.isSessionValid(NotesSettings.TIMEOUT_UNLIMITED))
    }

    @Test
    fun `one hour and one day boundaries`() {
        sm.markUnlocked()
        now += 59 * 60_000L
        assertTrue(sm.isSessionValid(NotesSettings.TIMEOUT_1H))
        now += 2 * 60_000L
        assertFalse(sm.isSessionValid(NotesSettings.TIMEOUT_1H))
        assertTrue(sm.isSessionValid(NotesSettings.TIMEOUT_1D))
    }

    @Test
    fun `lock invalidates an active session`() {
        sm.markUnlocked()
        assertTrue(sm.isSessionValid(NotesSettings.TIMEOUT_1H))
        sm.lock()
        assertFalse(sm.isSessionValid(NotesSettings.TIMEOUT_1H))
    }
}
