package com.voxapps.design.icon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a typed icon is allowed to be before it is stored. */
class VoxIconsTest {

    @Test
    fun `an emoji survives unchanged`() {
        assertEquals("🛒", VoxIcons.sanitised("🛒"))
    }

    /** Several code units per glyph, so the limit has to leave room for more than one character. */
    @Test
    fun `a flag and a skin tone survive`() {
        assertEquals("🇷🇴", VoxIcons.sanitised("🇷🇴"))
        assertEquals("👍🏽", VoxIcons.sanitised("👍🏽"))
    }

    @Test
    fun `surrounding space is not part of it`() {
        assertEquals("🛒", VoxIcons.sanitised("  🛒 "))
    }

    /** A sentence is not an icon, and would push every row carrying it out of shape. */
    @Test
    fun `a sentence is cut down`() {
        val cut = VoxIcons.sanitised("groceries and other weekly shopping")
        assertTrue("something is kept", !cut.isNullOrEmpty())
        assertTrue("but not a sentence", cut!!.length <= 8)
    }

    @Test
    fun `nothing typed is no icon`() {
        assertNull(VoxIcons.sanitised(null))
        assertNull(VoxIcons.sanitised(""))
        assertNull(VoxIcons.sanitised("   "))
    }

    @Test
    fun `plain text is allowed, since a shorthand need not be an emoji`() {
        assertEquals("RO", VoxIcons.sanitised("RO"))
    }

    @Test
    fun `the offered set has no duplicates`() {
        assertEquals(VoxIcons.COMMON.size, VoxIcons.COMMON.distinct().size)
    }

    @Test
    fun `every offered icon survives its own sanitising`() {
        for (icon in VoxIcons.COMMON) assertEquals(icon, VoxIcons.sanitised(icon))
    }
}
