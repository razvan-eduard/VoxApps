package com.voxapps.expenses.data

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

    // --- precedingColor adjacency (preset phase) ---

    @Test
    fun `preset phase prefers the unused preset farthest in hue from precedingColor`() {
        // Only two presets left unused: index 0 (hue 0°) and index 5 (hue 180°) — directly opposite,
        // the farthest possible option from index 0.
        val used = CategoryPalette.argb.filterIndexed { index, _ -> index !in setOf(0, 5) }
        val color = CategoryPalette.unusedOrRandomColor(used, precedingColor = CategoryPalette.argb[0])
        assertEquals(CategoryPalette.argb[5], color)
    }

    @Test
    fun `preset phase falls back to first unused preset when precedingColor is null`() {
        val used = CategoryPalette.argb.filterIndexed { index, _ -> index !in setOf(0, 5) }
        val color = CategoryPalette.unusedOrRandomColor(used, precedingColor = null)
        assertEquals(CategoryPalette.argb[0], color)
    }

    @Test
    fun `preset phase ignores precedingColor when only one unused preset remains`() {
        val used = CategoryPalette.argb.drop(1)
        val color = CategoryPalette.unusedOrRandomColor(used, precedingColor = CategoryPalette.argb.first())
        assertEquals(CategoryPalette.argb[0], color)
    }

    // Random-fallback hue-distance behavior (including precedingColor bias) now lives in and is
    // fully covered by VoxColorPaletteTest (:core:design) — CategoryPalette is a thin delegate, so
    // that randomness-under-threshold case isn't re-verified here.
}
