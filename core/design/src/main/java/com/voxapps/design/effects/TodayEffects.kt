package com.voxapps.design.effects

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import com.voxapps.design.effects.particles.EmitterShape
import com.voxapps.design.effects.particles.ParticleEffectConfig
import com.voxapps.design.effects.particles.ParticleEffectPresets
import com.voxapps.design.effects.particles.ParticleField

/**
 * Highlights an app's "today" element with a lightweight, dependency-free native particle effect —
 * no Lottie, no bundled assets: a small `Canvas`-driven particle engine
 * (`com.voxapps.design.effects.particles`) renders whichever [effect] preset is chosen (Fire/Glow/
 * Waves/Rainbow/Neon Pulse), spawning particles from wherever [style] resolves to (a ring around
 * [content]'s bounds, its interior, both, or neither), colored from [primaryColor]/[secondaryColor]
 * for presets that use them (see [TodayEffect.usesColor]/[TodayEffect.usesGradient]).
 * [speedMultiplier] scales the whole animation's tempo uniformly across every preset.
 *
 * In-app only: Glance/RemoteViews (this repo's home-screen-widget framework) cannot run a per-frame
 * particle loop any more than it could run Lottie, so this must never be called from widget code —
 * the widgets' existing "today" pill/border treatment is a separate, static, already-shipped visual.
 */
@Composable
fun ApplyTodayEffect(
    enabled: Boolean,
    elementName: String,
    effect: TodayEffect,
    primaryColor: Color,
    secondaryColor: Color? = null,
    style: TodayEffectStyle = TodayEffectStyle.RING,
    shape: Shape = RectangleShape,
    speedMultiplier: Float = 1f,
    // Callers whose content relies on a layout-scope modifier (e.g. RowScope.weight in a per-day
    // header row) must pass that modifier here rather than on `content` itself — this composable
    // always wraps content in its own Box (even when the effect is off), so a modifier placed
    // inside `content` would no longer be a direct child of the original Row/Column.
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    if (!enabled || effect == TodayEffect.NONE || style == TodayEffectStyle.NONE) {
        Box(modifier = modifier) { content() }
        return
    }

    val emitterShapes = when (style) {
        TodayEffectStyle.NONE -> emptyList() // unreachable, guarded above
        TodayEffectStyle.RING -> listOf(EmitterShape.Ring())
        TodayEffectStyle.BACKGROUND -> listOf(EmitterShape.Rectangle)
        TodayEffectStyle.FULL -> listOf(EmitterShape.Ring(), EmitterShape.Rectangle)
    }

    val config: ParticleEffectConfig = when (effect) {
        TodayEffect.NONE -> return // unreachable, guarded above
        TodayEffect.FIRE -> ParticleEffectPresets.fire(emitterShapes, primaryColor, secondaryColor)
        TodayEffect.GLOW -> ParticleEffectPresets.glow(emitterShapes, primaryColor, secondaryColor)
        TodayEffect.WAVES -> ParticleEffectPresets.waves(emitterShapes, primaryColor, secondaryColor)
        TodayEffect.RAINBOW -> ParticleEffectPresets.rainbow(emitterShapes)
        TodayEffect.NEON_PULSE -> ParticleEffectPresets.neonPulse(emitterShapes, primaryColor)
    }

    // Glow/Neon Pulse read as light radiating from behind the element, so they're clipped to its
    // shape and drawn underneath. Fire/Waves/Rainbow read better as an overlay allowed to spill
    // slightly past the element's edges (flames licking upward, ripples/sparks escaping the bounds),
    // so they're drawn on top, unclipped.
    val drawBehindContent = effect == TodayEffect.GLOW || effect == TodayEffect.NEON_PULSE

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (drawBehindContent) {
            ParticleField(
                config = config,
                modifier = Modifier.matchParentSize().clip(shape),
                speedMultiplier = speedMultiplier
            )
        }
        content()
        if (!drawBehindContent) {
            ParticleField(
                config = config,
                modifier = Modifier.matchParentSize(),
                speedMultiplier = speedMultiplier
            )
        }
    }
}
