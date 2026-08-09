package com.voxapps.design.effects.particles

import kotlin.math.sin

/**
 * How a [Particle] evolves each frame. Returns the updated particle, or `null` to remove it. Every
 * preset's "physics" — fire rising, waves oscillating, glow pulsing — is just a different function
 * built here; new effects are new functions, not engine changes. [deltaSeconds] and [elapsedSeconds]
 * are already scaled by [ParticleField]'s `speedMultiplier`, so motion functions don't need their own
 * speed handling.
 */
typealias ParticleMotion = (particle: Particle, deltaSeconds: Float, elapsedSeconds: Float) -> Particle?

private const val MIN_RADIUS = 0.5f

/** Rises, drifts gently side to side, shrinks and fades over its lifetime — Fire. */
fun riseAndShrink(
    riseSpeedPxPerSec: Float = 70f,
    driftPxPerSec: Float = 10f,
    lifeDecayPerSec: Float = 0.5f,
    shrinkFractionPerSec: Float = 0.4f
): ParticleMotion = motion@{ particle, deltaSeconds, _ ->
    val life = particle.life - lifeDecayPerSec * deltaSeconds
    val radius = particle.radius * (1f - shrinkFractionPerSec * deltaSeconds).coerceAtLeast(0f)
    if (life <= 0f || radius < MIN_RADIUS) return@motion null
    val driftDirection = if (particle.spawnX.toInt() % 2 == 0) 1f else -1f
    particle.copy(
        y = particle.y - riseSpeedPxPerSec * deltaSeconds,
        x = particle.x + driftPxPerSec * deltaSeconds * driftDirection,
        radius = radius,
        life = life
    )
}

/** Stays near its spawn point, drifting up/down in a smooth sine pulse — Glow. */
fun pulseInPlace(
    amplitudePx: Float = 8f,
    frequencyHz: Float = 0.8f,
    lifeDecayPerSec: Float = 0.15f
): ParticleMotion = motion@{ particle, deltaSeconds, elapsedSeconds ->
    val life = particle.life - lifeDecayPerSec * deltaSeconds
    if (life <= 0f) return@motion null
    val offset = amplitudePx * sin(elapsedSeconds * frequencyHz * TWO_PI)
    particle.copy(y = particle.spawnY + offset, life = life)
}

/** Oscillates vertically as a function of its own spawn X and the field's elapsed time, producing a
 *  traveling ripple across many particles spawned along a line/rectangle — Waves. */
fun waveDrift(
    amplitudePx: Float = 16f,
    wavelengthPx: Float = 90f,
    speed: Float = 2.2f,
    lifeDecayPerSec: Float = 0.2f
): ParticleMotion = motion@{ particle, deltaSeconds, elapsedSeconds ->
    val life = particle.life - lifeDecayPerSec * deltaSeconds
    if (life <= 0f) return@motion null
    val phase = (particle.spawnX / wavelengthPx) * TWO_PI + elapsedSeconds * speed
    val offset = amplitudePx * sin(phase)
    particle.copy(y = particle.spawnY + offset, life = life)
}

/** Doesn't move at all — radius/alpha are handled entirely by the paired [ParticleColorFn] — Neon
 *  Pulse's static flicker, kept separate from [pulseInPlace] since neon reads better perfectly still. */
fun staticFlicker(lifeDecayPerSec: Float = 0.1f): ParticleMotion = motion@{ particle, deltaSeconds, _ ->
    val life = particle.life - lifeDecayPerSec * deltaSeconds
    if (life <= 0f) return@motion null
    particle.copy(life = life)
}

private const val TWO_PI = (Math.PI * 2).toFloat()
