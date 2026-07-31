package com.voxapps.design.effects.particles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParticleMotionTest {

    private fun newParticle(x: Float = 10f, y: Float = 10f, radius: Float = 5f, life: Float = 1f) =
        Particle(x = x, y = y, spawnX = x, spawnY = y, radius = radius, life = life)

    @Test
    fun `riseAndShrink decreases life, rises, and shrinks each step`() {
        val motion = riseAndShrink(riseSpeedPxPerSec = 60f, lifeDecayPerSec = 0.5f, shrinkFractionPerSec = 0.4f)
        val start = newParticle(y = 100f, radius = 10f, life = 1f)

        val updated = motion(start, 1f, 1f)

        assertTrue("updated particle should not be removed after one step", updated != null)
        assertEquals(0.5f, updated!!.life, 1e-4f)
        assertTrue("particle should rise (y decreases)", updated.y < start.y)
        assertTrue("particle should shrink", updated.radius < start.radius)
    }

    @Test
    fun `riseAndShrink removes particle once life is exhausted`() {
        val motion = riseAndShrink(lifeDecayPerSec = 0.5f)
        val start = newParticle(life = 0.4f)

        assertNull(motion(start, 1f, 1f))
    }

    @Test
    fun `riseAndShrink removes particle once radius shrinks below the floor`() {
        val motion = riseAndShrink(lifeDecayPerSec = 0f, shrinkFractionPerSec = 10f)
        val start = newParticle(radius = 1f, life = 1f)

        assertNull(motion(start, 1f, 1f))
    }

    @Test
    fun `pulseInPlace keeps the particle within amplitude of its spawn point`() {
        val amplitude = 8f
        val motion = pulseInPlace(amplitudePx = amplitude, frequencyHz = 1f, lifeDecayPerSec = 0.1f)
        val start = newParticle(y = 50f)

        var particle: Particle? = start
        for (frame in 1..30) {
            particle = motion(particle!!, 0.05f, frame * 0.05f)
            if (particle == null) break
            assertTrue(
                "offset from spawnY should stay within amplitude",
                kotlin.math.abs(particle.y - particle.spawnY) <= amplitude + 1e-3f
            )
        }
    }

    @Test
    fun `waveDrift gives different particles different phases for different spawnX`() {
        val motion = waveDrift(amplitudePx = 10f, wavelengthPx = 50f, speed = 1f, lifeDecayPerSec = 0f)
        val left = newParticle(x = 0f, y = 0f)
        val right = newParticle(x = 25f, y = 0f)

        val updatedLeft = motion(left, 0.016f, 1f)!!
        val updatedRight = motion(right, 0.016f, 1f)!!

        assertTrue(
            "particles spawned at different X should not be perfectly in phase",
            kotlin.math.abs(updatedLeft.y - updatedRight.y) > 1e-3f
        )
    }

    @Test
    fun `staticFlicker never moves the particle, only ages it`() {
        val motion = staticFlicker(lifeDecayPerSec = 0.2f)
        val start = newParticle(x = 42f, y = 99f, radius = 7f, life = 1f)

        val updated = motion(start, 1f, 1f)

        assertTrue(updated != null)
        assertEquals(start.x, updated!!.x, 0f)
        assertEquals(start.y, updated.y, 0f)
        assertEquals(start.radius, updated.radius, 0f)
        assertEquals(0.8f, updated.life, 1e-4f)
    }
}
