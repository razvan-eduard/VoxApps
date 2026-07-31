package com.voxapps.design.effects.particles

/**
 * A single animated point in a [ParticleField]: position, size, and remaining lifetime (`1f` at
 * spawn, `0f` when expired). [spawnX]/[spawnY] are recorded at creation so a [ParticleMotion] can
 * compute offsets relative to where the particle started (rising, oscillating, pulsing in place)
 * without needing extra per-effect fields on this class.
 */
data class Particle(
    val x: Float,
    val y: Float,
    val spawnX: Float,
    val spawnY: Float,
    val radius: Float,
    val life: Float,
    val vx: Float = 0f,
    val vy: Float = 0f
)
