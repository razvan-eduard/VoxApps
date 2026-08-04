package com.voxapps.design.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.voxapps.design.color.VoxColorPalette
import com.voxapps.design.color.VoxColorSwatchPicker
import com.voxapps.design.effects.ApplyTodayEffect
import com.voxapps.design.effects.TodayEffect
import com.voxapps.design.effects.TodayEffectStyle
import java.time.LocalDate

/** Preset swatches (index into [VoxColorPalette.presets]) auto-applied the moment a color-using
 *  effect is turned on from [TodayEffect.NONE] — orange for Fire, cyan for Glow, blue for Waves,
 *  magenta for Neon Pulse — so the color picker never opens with nothing visibly selected (the raw
 *  default color didn't line up with any preset swatch, so switching effects left the picker looking
 *  unset even though a color was stored). Rainbow has no entry since [TodayEffect.usesColor] is
 *  false for it — the pre-select logic is skipped generically, not by name. */
private const val FIRE_PRESET_INDEX = 1
private const val GLOW_PRESET_INDEX = 5
private const val WAVES_PRESET_INDEX = 6
private const val NEON_PULSE_PRESET_INDEX = 8

private const val SPEED_MIN = 0.25f
private const val SPEED_MAX = 3f

data class TodayEffectStrings(
    val sectionLabel: String,
    val noneLabel: String,
    val fireLabel: String,
    val glowLabel: String,
    val wavesLabel: String,
    val rainbowLabel: String,
    val neonPulseLabel: String,
    val styleLabel: String,
    val styleNoneLabel: String,
    val ringLabel: String,
    val backgroundLabel: String,
    val fullLabel: String,
    val primaryColorLabel: String,
    val gradientLabel: String,
    val gradientDesc: String,
    val secondaryColorLabel: String,
    val speedLabel: String,
    val showInWidgetLabel: String? = null,
    val customColorDialogTitle: String,
    val customColorUseLabel: String,
    val customColorCancelLabel: String,
    val cancelLabel: String,
    val hueLabel: String,
    val saturationLabel: String,
    val brightnessLabel: String
)

/** Bundles the "today effect" settings values/callbacks passed into [ThemeSettingsScreen] — kept as
 *  one parameter object rather than many separate ones since it's already conditionally-optional
 *  there (only Calendar/Expenses/Notes pass one). */
data class TodayEffectSettings(
    val effect: TodayEffect,
    val style: TodayEffectStyle,
    val primaryColor: Long,
    val secondaryColor: Long?,
    val speedMultiplier: Float,
    /** Whether the effect also draws in the home-screen widget's static "today" card, independent
     *  of whether it's on in-app. `null` (with [onShowInWidgetChange] also `null`) hides the toggle
     *  entirely — for callers (Notes) whose widget has no today-effect rendering to gate at all. */
    val showInWidget: Boolean? = null,
    val onEffectChange: (TodayEffect) -> Unit,
    val onStyleChange: (TodayEffectStyle) -> Unit,
    val onPrimaryColorChange: (Long) -> Unit,
    val onSecondaryColorChange: (Long?) -> Unit,
    val onSpeedMultiplierChange: (Float) -> Unit,
    val onShowInWidgetChange: ((Boolean) -> Unit)? = null,
    val strings: TodayEffectStrings
)

/** The "highlight today" section on [ThemeSettingsScreen]: an effect picker (None/Fire/Glow), and —
 *  once one is chosen — a primary color picker plus an optional second color for a gradient. Reuses
 *  the existing [VoxColorSwatchPicker] (already used for widget-border color) rather than building a
 *  new one. The effect itself doesn't render anything yet — see [com.voxapps.design.effects.ApplyTodayEffect]. */
@Composable
fun TodayEffectSettingsCard(settings: TodayEffectSettings, modifier: Modifier = Modifier) {
    val strings = settings.strings
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(strings.sectionLabel, style = MaterialTheme.typography.labelLarge)

        // Preview + effect picker + style picker are one tight visual group (what does it look like,
        // and where) — spaced closer together than the major sections below (color, gradient, speed).
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TodayEffectPreview(settings)

            // FlowRow, not Row — 6 chips (None/Fire/Glow/Waves/Rainbow/Neon Pulse) don't fit on one
            // line on a phone-width screen; a plain Row would let the overflow chips spill past the
            // screen edge instead of wrapping, and left a large empty gap below before Style.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val options = listOf(
                    TodayEffect.NONE to strings.noneLabel,
                    TodayEffect.FIRE to strings.fireLabel,
                    TodayEffect.GLOW to strings.glowLabel,
                    TodayEffect.WAVES to strings.wavesLabel,
                    TodayEffect.RAINBOW to strings.rainbowLabel,
                    TodayEffect.NEON_PULSE to strings.neonPulseLabel
                )
                options.forEach { (effect, label) ->
                    FilterChip(
                        selected = settings.effect == effect,
                        onClick = {
                            if (settings.effect == TodayEffect.NONE && effect.usesColor) {
                                val presetIndex = when (effect) {
                                    TodayEffect.FIRE -> FIRE_PRESET_INDEX
                                    TodayEffect.GLOW -> GLOW_PRESET_INDEX
                                    TodayEffect.WAVES -> WAVES_PRESET_INDEX
                                    TodayEffect.NEON_PULSE -> NEON_PULSE_PRESET_INDEX
                                    else -> null
                                }
                                presetIndex?.let { settings.onPrimaryColorChange(VoxColorPalette.presets[it]) }
                            }
                            settings.onEffectChange(effect)
                        },
                        label = { Text(label) }
                    )
                }
            }

            if (settings.effect != TodayEffect.NONE) {
                Text(strings.styleLabel, style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val styleOptions = listOf(
                        TodayEffectStyle.NONE to strings.styleNoneLabel,
                        TodayEffectStyle.RING to strings.ringLabel,
                        TodayEffectStyle.BACKGROUND to strings.backgroundLabel,
                        TodayEffectStyle.FULL to strings.fullLabel
                    )
                    styleOptions.forEach { (style, label) ->
                        FilterChip(
                            selected = settings.style == style,
                            onClick = { settings.onStyleChange(style) },
                            label = { Text(label) }
                        )
                    }
                }
            }
        }

        if (settings.effect != TodayEffect.NONE) {
            if (settings.effect.usesColor) {
                Text(strings.primaryColorLabel, style = MaterialTheme.typography.bodyMedium)
                VoxColorSwatchPicker(
                    selectedColor = settings.primaryColor,
                    onColorSelected = settings.onPrimaryColorChange,
                    collapsible = false,
                    customColorDialogTitle = strings.customColorDialogTitle,
                    customColorUseLabel = strings.customColorUseLabel,
                    customColorCancelLabel = strings.customColorCancelLabel,
                    customColorHueLabel = strings.hueLabel,
                    customColorSaturationLabel = strings.saturationLabel,
                    customColorBrightnessLabel = strings.brightnessLabel
                )

                if (settings.effect.usesGradient) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(strings.gradientLabel, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                strings.gradientDesc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.secondaryColor != null,
                            onCheckedChange = { enabled ->
                                settings.onSecondaryColorChange(if (enabled) settings.primaryColor else null)
                            }
                        )
                    }

                    if (settings.secondaryColor != null) {
                        Text(strings.secondaryColorLabel, style = MaterialTheme.typography.bodyMedium)
                        VoxColorSwatchPicker(
                            selectedColor = settings.secondaryColor,
                            onColorSelected = settings.onSecondaryColorChange,
                            collapsible = false,
                            customColorDialogTitle = strings.customColorDialogTitle,
                            customColorUseLabel = strings.customColorUseLabel,
                            customColorCancelLabel = strings.customColorCancelLabel,
                            customColorHueLabel = strings.hueLabel,
                            customColorSaturationLabel = strings.saturationLabel,
                            customColorBrightnessLabel = strings.brightnessLabel
                        )
                    }
                }
            }

            if (settings.style != TodayEffectStyle.NONE) {
                Text(
                    "${strings.speedLabel} (${String.format(java.util.Locale.US, "%.2f", settings.speedMultiplier)}x)",
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = settings.speedMultiplier,
                    onValueChange = settings.onSpeedMultiplierChange,
                    valueRange = SPEED_MIN..SPEED_MAX
                )
            }

            val showInWidget = settings.showInWidget
            val onShowInWidgetChange = settings.onShowInWidgetChange
            if (showInWidget != null && onShowInWidgetChange != null && strings.showInWidgetLabel != null) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        strings.showInWidgetLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = showInWidget, onCheckedChange = onShowInWidgetChange)
                }
            }
        }
    }
}

/** A live sample of the currently selected effect/style/color(s), rendered with the exact same
 *  [ApplyTodayEffect] used at every real call site — not a mocked/illustrative rendering, so it's
 *  always an accurate preview of what "today" will actually look like, including honestly showing
 *  nothing when [TodayEffectSettings.effect] or [TodayEffectSettings.style] is off. */
@Composable
private fun TodayEffectPreview(settings: TodayEffectSettings) {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        ApplyTodayEffect(
            enabled = true,
            elementName = "today_effect_settings_preview",
            effect = settings.effect,
            style = settings.style,
            primaryColor = Color(settings.primaryColor.toInt()),
            secondaryColor = settings.secondaryColor?.let { Color(it.toInt()) },
            speedMultiplier = settings.speedMultiplier,
            shape = MaterialTheme.shapes.medium,
            // Glow/Neon Pulse draw behind content, clipped to this Box's own bounds — sized larger
            // than the inner day-cell below so that layer has a visible margin to radiate into
            // instead of being fully covered by the cell's opaque background.
            modifier = Modifier.size(96.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = LocalDate.now().dayOfMonth.toString(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
