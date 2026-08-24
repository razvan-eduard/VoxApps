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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private val SWATCH_OUTER_SIZE = 36.dp
private val SWATCH_RING_WIDTH = 2.dp
private val SWATCH_RING_GAP = 3.dp
private val SWATCH_SPACING = 10.dp
private val SWATCH_STACK_PEEK_OFFSET = 10.dp
private val SWATCH_STACK_PEEK_ALPHAS = listOf(0.75f, 0.5f, 0.3f)

/**
 * Shared preset-color picker used by every satellite's category/layer "add"/"edit" dialogs
 * (vox-notes, vox-expenses, vox-calendar). A scrollable row of [presetColors] swatches plus a
 * trailing "custom" entry that opens [VoxCustomColorDialog] — its result feeds back through
 * [onColorSelected] exactly like tapping a preset, so callers only ever handle one color-changed
 * callback regardless of which path picked it.
 *
 * When [collapsible] is true (the default), the picker starts collapsed — a single swatch showing
 * [selectedColor] with a few others peeking out from behind it — and expands into the full row on
 * tap, so an entity-color picker doesn't dominate a form before the user has asked to change it.
 * [showSelectionRing] controls whether the currently-selected preset draws its ring once expanded;
 * callers whose color is reassigned often/randomly (e.g. a single to-do task) can turn this off so
 * the ring doesn't anchor attention on a color that's about to be replaced anyway.
 *
 * By default the expanded/collapsed state is managed internally. Callers whose surrounding
 * composition churns a lot on selection (e.g. reacting to a Room `Flow` write triggered by
 * [onColorSelected] itself) can instead hoist it via [expanded]/[onExpandedChange] so the flag lives
 * somewhere more stable than this composable's own slot.
 *
 * [shape] outlines every swatch (see [VoxSwatchShapes]) — circles by default. A caller can pass a
 * different shape to give the whole picker a second meaning beyond color: vox-calendar passes
 * [VoxSwatchShapes.Star] for the Main calendar, matching the star its sidebar dot switches to.
 */
@Composable
fun VoxColorSwatchPicker(
    selectedColor: Long,
    onColorSelected: (Long) -> Unit,
    modifier: Modifier = Modifier,
    presetColors: List<Long> = VoxColorPalette.presets,
    collapsible: Boolean = true,
    /** Which way the swatches run. A column is the same picker on its side — vox-calendar's to-do
     *  card needs one on the trailing edge of a row-shaped card, and used to carry its own copy of
     *  this component to get it. */
    orientation: Orientation = Orientation.Horizontal,
    /** Swatch footprint. Smaller where the picker sits beside content rather than under it. */
    swatchSize: Dp = SWATCH_OUTER_SIZE,
    expanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    showSelectionRing: Boolean = true,
    shape: Shape = CircleShape,
    customColorDialogTitle: String = "Custom color",
    customColorUseLabel: String = "Use this color",
    customColorCancelLabel: String = "Cancel",
    customColorHueLabel: String = "Hue",
    customColorSaturationLabel: String = "Saturation",
    customColorBrightnessLabel: String = "Brightness"
) {
    var internalExpanded by remember { mutableStateOf(!collapsible) }
    val expandedState = expanded ?: internalExpanded
    val setExpanded: (Boolean) -> Unit = { value ->
        onExpandedChange?.invoke(value) ?: run { internalExpanded = value }
    }
    var showCustomDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Starts scrolled to whichever swatch is already selected (the randomly-picked default for a
    // new category, or the category's own color when editing) so it isn't hidden off-screen the
    // moment the row expands.
    LaunchedEffect(expandedState) {
        if (!expandedState) return@LaunchedEffect
        val index = presetColors.indexOf(selectedColor)
        if (index > 0) listState.scrollToItem(index)
    }

    // The two directions share their item content exactly; only the container and which axis the
    // spacing applies to differ, which is why one component can be both.
    val swatches: LazyListScope.() -> Unit = {
        items(presetColors) { color ->
            VoxColorSwatch(
                color = color,
                selected = showSelectionRing && color == selectedColor,
                onClick = { onColorSelected(color) },
                shape = shape,
                size = swatchSize
            )
        }
        item {
            VoxCustomColorEntry(onClick = { showCustomDialog = true }, size = swatchSize)
        }
    }

    if (expandedState) {
        if (orientation == Orientation.Vertical) {
            LazyColumn(
                state = listState,
                modifier = modifier,
                verticalArrangement = Arrangement.spacedBy(SWATCH_SPACING),
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(horizontal = 4.dp),
                content = swatches
            )
        } else {
            LazyRow(
                state = listState,
                modifier = modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SWATCH_SPACING),
                contentPadding = PaddingValues(vertical = 4.dp),
                content = swatches
            )
        }
    } else {
        CollapsedSwatchStack(
            selectedColor = selectedColor,
            peekColors = presetColors.filter { it != selectedColor }.take(SWATCH_STACK_PEEK_ALPHAS.size),
            onClick = { setExpanded(true) },
            modifier = modifier,
            shape = shape,
            orientation = orientation,
            size = swatchSize
        )
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

/** Collapsed preview for [VoxColorSwatchPicker]: [selectedColor] up front, a few [peekColors]
 *  fanned out behind it (decreasing size/alpha) to hint that tapping reveals more. */
@Composable
private fun CollapsedSwatchStack(
    selectedColor: Long,
    peekColors: List<Long>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    orientation: Orientation = Orientation.Horizontal,
    size: Dp = SWATCH_OUTER_SIZE
) {
    // The peeks fan out along whichever axis the expanded picker will use, so the collapsed state
    // points at where the rest of the swatches are about to appear.
    val vertical = orientation == Orientation.Vertical
    val spread = SWATCH_STACK_PEEK_OFFSET * peekColors.size
    Box(
        modifier = modifier
            .height(if (vertical) size + spread else size)
            .width(if (vertical) size else size + spread)
            .clickable(onClick = onClick)
    ) {
        peekColors.forEachIndexed { index, color ->
            val offset = SWATCH_STACK_PEEK_OFFSET * (index + 1)
            Box(
                modifier = Modifier
                    .offset(x = if (vertical) 0.dp else offset, y = if (vertical) offset else 0.dp)
                    .size(size)
                    .clip(shape)
                    .background(Color(color.toInt()).copy(alpha = SWATCH_STACK_PEEK_ALPHAS[index]))
            )
        }
        Box(
            modifier = Modifier
                .size(size)
                .clip(shape)
                .background(Color(selectedColor.toInt()))
        )
    }
}

/** A fixed-footprint swatch so selecting one never reflows the row: unselected swatches fill the
 *  whole [SWATCH_OUTER_SIZE] circle; the selected one draws a ring on the outer edge and shrinks
 *  the solid color inward, leaving a visible gap — a genuine outline around the color rather than
 *  a border drawn on the color itself. */
@Composable
internal fun VoxColorSwatch(
    color: Long,
    selected: Boolean,
    onClick: () -> Unit,
    shape: Shape = CircleShape,
    size: Dp = SWATCH_OUTER_SIZE
) {
    Box(
        modifier = Modifier
            .size(size)
            .then(if (selected) Modifier.border(SWATCH_RING_WIDTH, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(if (selected) size - (SWATCH_RING_WIDTH + SWATCH_RING_GAP) * 2 else size)
                .clip(shape)
                .background(Color(color.toInt()))
        )
    }
}

@Composable
private fun VoxCustomColorEntry(onClick: () -> Unit, size: Dp = SWATCH_OUTER_SIZE) {
    Box(
        modifier = Modifier
            .size(size)
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
@OptIn(ExperimentalMaterial3Api::class)
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

    // A sheet rather than a dialog, so it goes away the way everything else that covers the screen
    // does: dragged down. A full-screen dialog can only be dismissed by finding its own close
    // button, which is a different gesture for the same intention.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
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
