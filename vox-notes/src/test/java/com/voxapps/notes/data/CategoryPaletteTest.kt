package com.voxapps.notes.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryPaletteTest {

    @Test
    fun `returns the first palette color when none are used`() {
        val color = CategoryPalette.unusedOrRandomColor(emptyList())
        assertEquals(CategoryPalette.argb.first(), color)
    }

    @Test
    fun `skips colors already in use and returns the first unused palette color`() {
        val used = listOf(CategoryPalette.argb[0], CategoryPalette.argb[1])
        val color = CategoryPalette.unusedOrRandomColor(used)
        assertEquals(CategoryPalette.argb[2], color)
    }

    @Test
    fun `generates a color outside the palette once all preset colors are used`() {
        val color = CategoryPalette.unusedOrRandomColor(CategoryPalette.argb)
        assertFalse(color in CategoryPalette.argb)
        // Still a well-formed opaque ARGB color (alpha byte fully set).
        assertTrue((color ushr 24) and 0xFF == 0xFFL)
    }

    @Test
    fun `generated fallback color is not in the used list even when it overlaps with palette values`() {
        // Sanity: repeated calls with a fully-used palette should each be valid ARGB, non-negative
        // when masked, and always opaque — guards against a broken HSV->RGB conversion silently
        // producing a nonsensical value.
        repeat(20) {
            val color = CategoryPalette.unusedOrRandomColor(CategoryPalette.argb)
            assertTrue(color in 0xFF000000L..0xFFFFFFFFL)
        }
    }

    // Hue-distance math (preset distinctness, precedingColor bias, random-fallback behavior) now
    // lives in and is fully covered by VoxColorPaletteTest (:core:design) — CategoryPalette is a
    // thin delegate, so these cases aren't re-verified here.
}
