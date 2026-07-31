package com.voxapps.design.effects.particles

/**
 * Everything a [ParticleField] needs to run one visual effect: where particles spawn, how they move,
 * how they're colored, and how many/how big. A preset (see `ParticleEffectPresets`) is just one of
 * these built from this package's primitives — new effects are new presets, not engine changes.
 */
data class ParticleEffectConfig(
    val emitterShapes: List<EmitterShape>,
    val motion: ParticleMotion,
    val color: ParticleColorFn,
    val spawnRatePerSecond: Float,
    val maxParticles: Int,
    val initialRadiusRange: ClosedFloatingPointRange<Float>
)
