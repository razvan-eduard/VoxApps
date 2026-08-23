package com.voxapps.calendarapp.ui.todo

import androidx.compose.ui.graphics.Color
import com.voxapps.design.VoxSemanticColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a task looks like once it is done.
 *
 * Flat grey and flat black, carrying nothing of the colour it was given: a tint of that colour says
 * "still that item, quieter", which makes a list of finished items read as a list of dimmed ones.
 */
class ItemToneTest {

    private val red = 0xFFE53935L
    private val blue = 0xFF1E88E5L

    @Test
    fun `a done item is flat grey whatever colour it was given`() {
        assertEquals(VoxSemanticColors.doneFill, itemTone(red, done = true, important = false))
        assertEquals(VoxSemanticColors.doneFill, itemTone(blue, done = true, important = false))
    }

    /** Two finished items are the same grey, so a finished list reads as one thing. */
    @Test
    fun `two done items of different colours look identical`() {
        assertEquals(
            itemTone(red, done = true, important = false),
            itemTone(blue, done = true, important = false)
        )
    }

    /** Importance changes weight, not the fill — a done star is the same grey as a done circle. */
    @Test
    fun `being important does not tint a done item`() {
        assertEquals(
            itemTone(red, done = true, important = false),
            itemTone(red, done = true, important = true)
        )
    }

    @Test
    fun `no hue survives being done`() {
        val tone = itemTone(red, done = true, important = false)
        assertEquals("grey has no channel that leads", tone.red, tone.green, 0.001f)
        assertEquals("grey has no channel that leads", tone.green, tone.blue, 0.001f)
    }

    // --- what is not done keeps its colour ---

    @Test
    fun `an unfinished item is its own colour`() {
        assertEquals(Color(red.toInt()), itemTone(red, done = false, important = false))
    }

    @Test
    fun `an important unfinished item is the same hue pressed deeper`() {
        val plain = itemTone(red, done = false, important = false)
        val important = itemTone(red, done = false, important = true)
        assertNotEquals(plain, important)
        assertTrue("deeper, not another colour", important.red < plain.red)
        assertTrue(important.green <= plain.green && important.blue <= plain.blue)
    }

    // --- the line around it ---

    @Test
    fun `a done item is outlined in flat black`() {
        val tone = itemTone(red, done = true, important = false)
        assertEquals(VoxSemanticColors.doneOutline, itemBorderColor(tone, done = true))
        assertEquals(VoxSemanticColors.doneOutline, itemBorderColor(itemTone(blue, true, true), done = true))
    }

    @Test
    fun `an unfinished item keeps its own darker outline`() {
        val tone = itemTone(red, done = false, important = false)
        val border = itemBorderColor(tone, done = false)
        assertNotEquals(VoxSemanticColors.doneOutline, border)
        assertEquals(toneBorderColor(tone), border)
    }

    @Test
    fun `the outline is opaque either way, so nothing is faded`() {
        assertEquals(1f, itemBorderColor(itemTone(red, true, false), done = true).alpha, 0.001f)
        assertEquals(1f, itemBorderColor(itemTone(red, false, false), done = false).alpha, 0.001f)
        assertEquals(1f, itemTone(red, done = true, important = false).alpha, 0.001f)
    }
}
