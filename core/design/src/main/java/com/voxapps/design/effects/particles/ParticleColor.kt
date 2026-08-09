package com.voxapps.design.effects.particles

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.voxapps.design.color.VoxColorPalette
import kotlin.math.sin

/**
 * Colors a [Particle] for the current frame, given its remaining [life] (`1f` newborn -> `0f`
 * expiring) and the field's global [elapsedSeconds]. Every preset's palette is just a different
 * function built here.
 */
typealias ParticleColorFn = (particle: Particle, life: Float, elapsedSeconds: Float) -> Color

/** A 3-stop thermal gradient (hot core -> cooling edge) derived from the picked color(s) as life runs
 *  out — Fire's look. [primary] is the hot end; [secondary], when set (gradient on), is the cold end,
 *  otherwise a darkened [primary] stands in, so Fire always reads as "cooling embers" even with a
 *  single picked color. */
fun thermalGradient(primary: Color, secondary: Color?): ParticleColorFn {
    val hot = primary
    val cold = secondary ?: darken(primary, 0.55f)
    val mid = lerp(cold, hot, 0.5f)
    return { _, life, _ ->
        val base = when {
            life > 0.6f -> lerp(mid, hot, (life - 0.6f) / 0.4f)
            life > 0.3f -> lerp(cold, mid, (life - 0.3f) / 0.3f)
            else -> cold
        }
        base.copy(alpha = life.coerceIn(0f, 1f))
    }
}

private fun darken(color: Color, factor: Float): Color = Color(
    red = color.red * (1f - factor),
    green = color.green * (1f - factor),
    blue = color.blue * (1f - factor),
    alpha = color.alpha
)

/** Cross-fades between [primary] and [secondary] over the particle's lifetime, or just fades
 *  [primary] out when there's no gradient second color — Glow's/Waves' look. */
fun crossfade(primary: Color, secondary: Color?): ParticleColorFn = { _, life, _ ->
    val base = if (secondary != null) lerp(secondary, primary, life) else primary
    base.copy(alpha = life.coerceIn(0f, 1f))
}

/** Self-cycling hue, independent of any picked color — Rainbow. Each particle's hue is offset by its
 *  spawn position so a field of them reads as a moving rainbow rather than a single flashing color. */
fun hueCycle(
    saturation: Float = 0.8f,
    value: Float = 0.9f,
    degreesPerSecond: Float = 60f
): ParticleColorFn = { particle, life, elapsedSeconds ->
    val hue = ((particle.spawnX * 0.6f + elapsedSeconds * degreesPerSecond) % 360f + 360f) % 360f
    val argb = VoxColorPalette.hsvToArgb(hue, saturation, value)
    Color(argb.toInt()).copy(alpha = life.coerceIn(0f, 1f))
}

/** A single hue pulsing in alpha — Neon Pulse. */
fun singleColorPulse(
    color: Color,
    minAlpha: Float = 0.35f,
    maxAlpha: Float = 1f,
    frequencyHz: Float = 1.2f
): ParticleColorFn = { _, life, elapsedSeconds ->
    val t = (sin(elapsedSeconds * frequencyHz * (Math.PI * 2).toFloat()) + 1f) / 2f
    val alpha = (minAlpha + (maxAlpha - minAlpha) * t) * life.coerceIn(0f, 1f)
    color.copy(alpha = alpha.coerceIn(0f, 1f))
}
