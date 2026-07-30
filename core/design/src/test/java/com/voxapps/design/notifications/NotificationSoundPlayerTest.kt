package com.voxapps.design.notifications

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationSoundPlayerTest {

    @Test
    fun `repeat count matches the selected length`() {
        assertEquals(1, NotificationSoundPlayer.repeatCountFor("SHORT"))
        assertEquals(2, NotificationSoundPlayer.repeatCountFor("MEDIUM"))
        assertEquals(3, NotificationSoundPlayer.repeatCountFor("LONG"))
    }

    @Test
    fun `unrecognized length falls back to a single repeat`() {
        assertEquals(1, NotificationSoundPlayer.repeatCountFor("nonsense"))
    }

    @Test
    fun `vibration pattern buzz count matches the selected length`() {
        // Each pattern is [initial delay, on, off, on, ...] — buzz count = (size - 1 + 1) / 2, i.e. every odd index.
        assertArrayEquals(longArrayOf(0, 200), NotificationSoundPlayer.vibrationPatternFor("SHORT"))
        assertArrayEquals(longArrayOf(0, 300, 200, 300), NotificationSoundPlayer.vibrationPatternFor("MEDIUM"))
        assertArrayEquals(longArrayOf(0, 400, 200, 400, 200, 400), NotificationSoundPlayer.vibrationPatternFor("LONG"))
    }

    @Test
    fun `unrecognized length falls back to the short vibration pattern`() {
        assertArrayEquals(longArrayOf(0, 200), NotificationSoundPlayer.vibrationPatternFor("nonsense"))
    }
}
