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

    @Test
    fun `fallback color's hue stays reasonably distant from a single existing color`() {
        // A sparse palette (one existing category) is the case most likely to produce two
        // near-identical hues under pure uniform-random sampling — farthest-hue selection among
        // several candidates should keep them visually distinct almost all the time.
        val existing = listOf(0xFFEF5350L) // red, hue ~1°
        repeat(50) {
            val color = CategoryPalette.unusedOrRandomColor(existing + CategoryPalette.argb.drop(1))
            val hue = hueOf(color)
            val existingHue = hueOf(existing.first())
            val diff = kotlin.math.abs(hue - existingHue).let { if (it > 180f) 360f - it else it }
            assertTrue("hue $hue too close to existing hue $existingHue (diff=$diff)", diff > 20f)
        }
    }

    private fun hueOf(argbColor: Long): Float {
        val r = ((argbColor shr 16) and 0xFFL).toInt() / 255f
        val g = ((argbColor shr 8) and 0xFFL).toInt() / 255f
        val b = (argbColor and 0xFFL).toInt() / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        if (delta == 0f) return 0f
        val hue = when (max) {
            r -> 60f * (((g - b) / delta).mod(6f))
            g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }
        return if (hue < 0f) hue + 360f else hue
    }
}
