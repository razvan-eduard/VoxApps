package com.voxapps.design.effects.particles

import androidx.compose.ui.graphics.Color

/**
 * The concrete "look" of each `TodayEffect` value, built purely from this package's generic
 * primitives ([EmitterShape]/[ParticleMotion]/[ParticleColorFn]) — this is the only place "what Fire
 * looks like" is actually defined; a new effect later is a new function here, not new engine code.
 * [emitterShapes] is supplied by the caller (resolved from `TodayEffectStyle`), since placement is
 * orthogonal to which preset is picked.
 */
object ParticleEffectPresets {

    fun fire(emitterShapes: List<EmitterShape>, primaryColor: Color, secondaryColor: Color?): ParticleEffectConfig =
        ParticleEffectConfig(
            emitterShapes = emitterShapes,
            motion = riseAndShrink(),
            color = thermalGradient(primaryColor, secondaryColor),
            spawnRatePerSecond = 18f,
            maxParticles = 60,
            initialRadiusRange = 3f..9f
        )

    fun glow(emitterShapes: List<EmitterShape>, primaryColor: Color, secondaryColor: Color?): ParticleEffectConfig =
        ParticleEffectConfig(
            emitterShapes = emitterShapes,
            motion = pulseInPlace(),
            color = crossfade(primaryColor, secondaryColor),
            spawnRatePerSecond = 4f,
            maxParticles = 14,
            initialRadiusRange = 8f..16f
        )

    fun waves(emitterShapes: List<EmitterShape>, primaryColor: Color, secondaryColor: Color?): ParticleEffectConfig =
        ParticleEffectConfig(
            emitterShapes = emitterShapes,
            motion = waveDrift(),
            color = crossfade(primaryColor, secondaryColor),
            spawnRatePerSecond = 10f,
            maxParticles = 40,
            initialRadiusRange = 3f..6f
        )

    /** No color params — self-cycling hue, ignores anything the user picked (see
     *  `TodayEffect.RAINBOW.usesColor == false`). */
    fun rainbow(emitterShapes: List<EmitterShape>): ParticleEffectConfig =
        ParticleEffectConfig(
            emitterShapes = emitterShapes,
            motion = pulseInPlace(amplitudePx = 4f, frequencyHz = 1.4f),
            color = hueCycle(),
            spawnRatePerSecond = 14f,
            maxParticles = 30,
            initialRadiusRange = 3f..7f
        )

    fun neonPulse(emitterShapes: List<EmitterShape>, primaryColor: Color): ParticleEffectConfig =
        ParticleEffectConfig(
            emitterShapes = emitterShapes,
            motion = staticFlicker(),
            color = singleColorPulse(primaryColor),
            spawnRatePerSecond = 6f,
            maxParticles = 16,
            initialRadiusRange = 6f..12f
        )
}
