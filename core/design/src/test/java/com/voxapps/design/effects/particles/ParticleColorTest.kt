package com.voxapps.design.effects.particles

import androidx.compose.ui.graphics.Color
import com.voxapps.design.color.VoxColorPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParticleColorTest {

    private val particle = Particle(x = 0f, y = 0f, spawnX = 0f, spawnY = 0f, radius = 5f, life = 1f)

    @Test
    fun `thermalGradient is fully opaque hot color at life 1 and fully transparent cold color at life 0`() {
        val hot = Color(0xFFFFEB3B)
        val secondary = Color(0xFFD50000)
        val colorFn = thermalGradient(hot, secondary)

        val atFullLife = colorFn(particle, 1f, 0f)
        assertEquals(hot.red, atFullLife.red, 1e-3f)
        assertEquals(hot.green, atFullLife.green, 1e-3f)
        assertEquals(hot.blue, atFullLife.blue, 1e-3f)
        assertEquals(1f, atFullLife.alpha, 1e-3f)

        val atZeroLife = colorFn(particle, 0f, 0f)
        assertEquals(0f, atZeroLife.alpha, 1e-3f)
    }

    @Test
    fun `crossfade without a secondary color just fades the primary`() {
        val primary = Color(0xFF00FF00)
        val colorFn = crossfade(primary, null)

        val result = colorFn(particle, 0.4f, 0f)

        assertEquals(primary.red, result.red, 1e-3f)
        assertEquals(primary.green, result.green, 1e-3f)
        assertEquals(primary.blue, result.blue, 1e-3f)
        assertEquals(0.4f, result.alpha, 1e-3f)
    }

    @Test
    fun `crossfade with a secondary color lerps from secondary to primary as life rises`() {
        val primary = Color(0xFFFFFFFF)
        val secondary = Color(0xFF000000)
        val colorFn = crossfade(primary, secondary)

        val atBirth = colorFn(particle, 1f, 0f)
        assertEquals(primary.red, atBirth.red, 1e-3f)

        val atDeath = colorFn(particle, 0f, 0f)
        assertEquals(secondary.red, atDeath.red, 1e-3f)
        assertEquals(0f, atDeath.alpha, 1e-3f)
    }

    @Test
    fun `hueCycle always produces a hue within the 0 to 360 range`() {
        val colorFn = hueCycle()
        repeat(20) { i ->
            val elapsed = i * 137f // arbitrary large, non-round values
            val color = colorFn(particle, 1f, elapsed)
            val argb = (0xFFL shl 24) or
                ((color.red * 255).toLong() shl 16) or
                ((color.green * 255).toLong() shl 8) or
                (color.blue * 255).toLong()
            val (hue, _, _) = VoxColorPalette.argbToHsv(argb)
            if (hue != null) {
                assertTrue("hue $hue should be within [0, 360)", hue >= 0f && hue < 360f)
            }
        }
    }

    @Test
    fun `singleColorPulse alpha stays within the configured min-max range, scaled by life`() {
        val color = Color(0xFFFF00FF)
        val minAlpha = 0.3f
        val maxAlpha = 0.9f
        val colorFn = singleColorPulse(color, minAlpha = minAlpha, maxAlpha = maxAlpha, frequencyHz = 1f)

        // Color's alpha channel is quantized to 8-bit precision internally, so a value set to
        // exactly maxAlpha can read back a hair above it — tolerance covers that quantization noise.
        val quantizationTolerance = 1f / 255f
        for (i in 0..20) {
            val result = colorFn(particle, 1f, i * 0.05f)
            assertTrue(
                "alpha ${result.alpha} should be within [$minAlpha, $maxAlpha]",
                result.alpha >= minAlpha - quantizationTolerance && result.alpha <= maxAlpha + quantizationTolerance
            )
        }
    }
}
