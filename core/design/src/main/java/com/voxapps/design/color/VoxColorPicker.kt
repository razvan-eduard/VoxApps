package com.voxapps.design.color

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private val SWATCH_OUTER_SIZE = 36.dp
private val SWATCH_RING_WIDTH = 2.dp
private val SWATCH_RING_GAP = 3.dp
private val SWATCH_SPACING = 10.dp

/**
 * Shared preset-color picker used by every satellite's category/layer "add"/"edit" dialogs
 * (vox-notes, vox-expenses, vox-calendar). A scrollable row of [presetColors] swatches plus a
 * trailing "custom" entry that opens [VoxCustomColorDialog] — its result feeds back through
 * [onColorSelected] exactly like tapping a preset, so callers only ever handle one color-changed
 * callback regardless of which path picked it.
 */
@Composable
fun VoxColorSwatchPicker(
    selectedColor: Long,
    onColorSelected: (Long) -> Unit,
    modifier: Modifier = Modifier,
    presetColors: List<Long> = VoxColorPalette.presets,
    customColorDialogTitle: String = "Custom color",
    customColorUseLabel: String = "Use this color",
    customColorCancelLabel: String = "Cancel",
    customColorHueLabel: String = "Hue",
    customColorSaturationLabel: String = "Saturation",
    customColorBrightnessLabel: String = "Brightness"
) {
    var showCustomDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Starts scrolled to whichever swatch is already selected (the randomly-picked default for a
    // new category, or the category's own color when editing) so it isn't hidden off-screen the
    // moment the picker first renders.
    LaunchedEffect(Unit) {
        val index = presetColors.indexOf(selectedColor)
        if (index > 0) listState.scrollToItem(index)
    }

    LazyRow(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SWATCH_SPACING),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        items(presetColors) { color ->
            VoxColorSwatch(color = color, selected = color == selectedColor, onClick = { onColorSelected(color) })
        }
        item {
            VoxCustomColorEntry(onClick = { showCustomDialog = true })
        }
    }

    if (showCustomDialog) {
        VoxCustomColorDialog(
            initialColor = selectedColor,
            presetColors = presetColors,
            title = customColorDialogTitle,
            useColorLabel = customColorUseLabel,
            cancelLabel = customColorCancelLabel,
            hueLabel = customColorHueLabel,
            saturationLabel = customColorSaturationLabel,
            valueLabel = customColorBrightnessLabel,
            onDismiss = { showCustomDialog = false },
            onConfirm = { color ->
                onColorSelected(color)
                showCustomDialog = false
            }
        )
    }
}

/** A fixed-footprint swatch so selecting one never reflows the row: unselected swatches fill the
 *  whole [SWATCH_OUTER_SIZE] circle; the selected one draws a ring on the outer edge and shrinks
 *  the solid color inward, leaving a visible gap — a genuine outline around the color rather than
 *  a border drawn on the color itself. */
@Composable
internal fun VoxColorSwatch(color: Long, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(SWATCH_OUTER_SIZE)
            .then(if (selected) Modifier.border(SWATCH_RING_WIDTH, MaterialTheme.colorScheme.primary, CircleShape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(if (selected) SWATCH_OUTER_SIZE - (SWATCH_RING_WIDTH + SWATCH_RING_GAP) * 2 else SWATCH_OUTER_SIZE)
                .clip(CircleShape)
                .background(Color(color.toInt()))
        )
    }
}

@Composable
private fun VoxCustomColorEntry(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(SWATCH_OUTER_SIZE)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Filled.Edit,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Full-screen custom-color picker: a live preview, the same [presetColors] row for a quick-pick
 * shortcut that seeds the sliders from that preset, then Hue/Saturation/Value sliders. Every label
 * defaults to English so a call site compiles without change, but should pass its own
 * `LocalLanguageManager`-resolved strings (matches [com.voxapps.design.rememberRequirementGate]'s
 * `requiredMessage` param convention) once translation keys exist for it.
 */
@Composable
fun VoxCustomColorDialog(
    initialColor: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
    presetColors: List<Long> = VoxColorPalette.presets,
    title: String = "Custom color",
    useColorLabel: String = "Use this color",
    cancelLabel: String = "Cancel",
    hueLabel: String = "Hue",
    saturationLabel: String = "Saturation",
    valueLabel: String = "Brightness"
) {
    val (initHue, initSaturation, initValue) = VoxColorPalette.argbToHsv(initialColor)
    var hue by remember { mutableFloatStateOf(initHue ?: 0f) }
    var saturation by remember { mutableFloatStateOf(initSaturation) }
    var value by remember { mutableFloatStateOf(initValue) }
    val currentColor = remember(hue, saturation, value) { VoxColorPalette.hsvToArgb(hue, saturation, value) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = cancelLabel)
                    }
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
                }

                Spacer(Modifier.height(24.dp))

                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(Color(currentColor.toInt()))
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                )

                Spacer(Modifier.height(24.dp))

                LazyRow(horizontalArrangement = Arrangement.spacedBy(SWATCH_SPACING), contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(presetColors) { preset ->
                        VoxColorSwatch(
                            color = preset,
                            selected = preset == currentColor,
                            onClick = {
                                val (h, s, v) = VoxColorPalette.argbToHsv(preset)
                                hue = h ?: 0f
                                saturation = s
                                value = v
                            }
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                Text(hueLabel, style = MaterialTheme.typography.labelLarge)
                Slider(value = hue, onValueChange = { hue = it }, valueRange = 0f..360f)

                Text(saturationLabel, style = MaterialTheme.typography.labelLarge)
                Slider(value = saturation, onValueChange = { saturation = it }, valueRange = 0f..1f)

                Text(valueLabel, style = MaterialTheme.typography.labelLarge)
                Slider(value = value, onValueChange = { value = it }, valueRange = 0f..1f)

                Spacer(Modifier.height(24.dp))

                Button(onClick = { onConfirm(currentColor) }, modifier = Modifier.fillMaxWidth()) {
                    Text(useColorLabel)
                }
            }
        }
    }
}
