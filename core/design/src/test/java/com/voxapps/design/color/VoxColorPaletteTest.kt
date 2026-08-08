package com.voxapps.design.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MIN_PRESET_HUE_DISTANCE = 18f

class VoxColorPaletteTest {

    @Test
    fun `every pair of presets clears the minimum hue distance`() {
        val hues = VoxColorPalette.presets.map { VoxColorPalette.argbToHsv(it).first!! }
        for (i in hues.indices) {
            for (j in i + 1 until hues.size) {
                val distance = VoxColorPalette.hueDistance(hues[i], hues[j])
                assertTrue(
                    "presets at index $i and $j are only $distance° apart",
                    distance >= MIN_PRESET_HUE_DISTANCE
                )
            }
        }
    }

    @Test
    fun `returns the first palette color when none are used`() {
        val color = VoxColorPalette.unusedOrRandomColor(emptyList())
        assertEquals(VoxColorPalette.presets.first(), color)
    }

    @Test
    fun `skips colors already in use and returns the first unused palette color`() {
        val used = listOf(VoxColorPalette.presets[0], VoxColorPalette.presets[1])
        val color = VoxColorPalette.unusedOrRandomColor(used)
        assertEquals(VoxColorPalette.presets[2], color)
    }

    @Test
    fun `generates a color outside the palette once all preset colors are used`() {
        val color = VoxColorPalette.unusedOrRandomColor(VoxColorPalette.presets)
        assertFalse(color in VoxColorPalette.presets)
        assertTrue((color ushr 24) and 0xFF == 0xFFL)
    }

    @Test
    fun `preset phase prefers the unused preset farthest in hue from precedingColor`() {
        val used = VoxColorPalette.presets.filterIndexed { index, _ -> index !in setOf(0, 5) }
        val color = VoxColorPalette.unusedOrRandomColor(used, precedingColor = VoxColorPalette.presets[0])
        assertEquals(VoxColorPalette.presets[5], color)
    }

    @Test
    fun `preset phase falls back to first unused preset when precedingColor is null`() {
        val used = VoxColorPalette.presets.filterIndexed { index, _ -> index !in setOf(0, 5) }
        val color = VoxColorPalette.unusedOrRandomColor(used, precedingColor = null)
        assertEquals(VoxColorPalette.presets[0], color)
    }

    @Test
    fun `random fallback stays clear of precedingColor's hue`() {
        val preceding = VoxColorPalette.presets[0]
        repeat(50) {
            val color = VoxColorPalette.unusedOrRandomColor(VoxColorPalette.presets, precedingColor = preceding)
            val hue = VoxColorPalette.argbToHsv(color).first!!
            val precedingHue = VoxColorPalette.argbToHsv(preceding).first!!
            val distance = VoxColorPalette.hueDistance(hue, precedingHue)
            // The 90° gate is enforced on the float hue before packing to ARGB; re-extracting hue
            // from the packed 8-bit-per-channel color loses a fraction of a degree, so allow a
            // small tolerance rather than requiring an exact >= 90 after the round-trip.
            assertTrue("hue $hue too close to preceding hue $precedingHue (diff=$distance)", distance >= 89f)
        }
    }

    @Test
    fun `hsvToArgb and argbToHsv round-trip a color`() {
        val color = VoxColorPalette.hsvToArgb(hue = 210f, saturation = 0.6f, value = 0.8f)
        val (hue, saturation, value) = VoxColorPalette.argbToHsv(color)
        assertTrue(kotlin.math.abs(hue!! - 210f) < 1f)
        assertTrue(kotlin.math.abs(saturation - 0.6f) < 0.02f)
        assertTrue(kotlin.math.abs(value - 0.8f) < 0.02f)
    }

    @Test
    fun `argbToHsv returns null hue for a gray`() {
        val (hue, _, _) = VoxColorPalette.argbToHsv(0xFF808080L)
        assertEquals(null, hue)
    }

    /**
     * Kept from vox-notes' copy of this test when the per-app palettes were removed: it guards the
     * HSV→RGB path rather than the choosing, so a broken conversion cannot silently hand back a
     * value that is not an opaque colour at all.
     */
    @Test
    fun `every generated fallback colour is a valid opaque ARGB value`() {
        repeat(20) {
            val color = VoxColorPalette.unusedOrRandomColor(VoxColorPalette.presets)
            assertTrue(color in 0xFF000000L..0xFFFFFFFFL)
        }
    }

    /** Kept from vox-expenses' copy: with one preset left, hue bias has nothing to choose between. */
    @Test
    fun `preset phase ignores precedingColor when only one unused preset remains`() {
        val used = VoxColorPalette.presets.drop(1)

        val color = VoxColorPalette.unusedOrRandomColor(used, precedingColor = VoxColorPalette.presets.first())

        assertEquals(VoxColorPalette.presets[0], color)
    }
}
