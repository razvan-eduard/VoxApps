package com.voxapps.calendarapp.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import com.voxapps.design.color.VoxColorSwatchPicker
import com.voxapps.design.color.VoxSwatchShapes
import com.voxapps.calendarapp.data.CalendarLayer
import com.voxapps.calendarapp.data.CalendarLayerKind
import com.voxapps.calendarapp.data.CalendarLayerPalette
import com.voxapps.calendarapp.data.CalendarRepository.LayerDeleteMode
import com.voxapps.calendarapp.data.ReminderOffsetsCodec
import com.voxapps.calendarapp.data.preferences.CalendarSettings
import com.voxapps.calendarapp.state.CalendarStateManager
import com.voxapps.calendarapp.state.CalendarViewMode
import java.text.DateFormat
import java.util.Date

private val SIDEBAR_WIDTH = 200.dp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun Sidebar(
    viewMode: CalendarViewMode,
    layers: List<CalendarLayer>,
    availableTags: List<String>,
    selectedTags: Set<String>,
    stateManager: CalendarStateManager,
    settings: CalendarSettings,
    modifier: Modifier = Modifier
) {
    val languageManager = LocalLanguageManager.current
    var showAddLayerDialog by remember { mutableStateOf(false) }
    var showSubscribeDialog by remember { mutableStateOf(false) }
    var addMenuExpanded by remember { mutableStateOf(false) }
    var editingLayer by remember { mutableStateOf<CalendarLayer?>(null) }
    var deleteCandidate by remember { mutableStateOf<CalendarLayer?>(null) }

    Surface(
        modifier = modifier.width(SIDEBAR_WIDTH).fillMaxHeight(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    ) {
        Column(modifier = Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp)) {
            val modes = listOf(
                CalendarViewMode.YEAR to "view_year",
                CalendarViewMode.MONTH to "view_month",
                CalendarViewMode.WEEK to "view_week",
                CalendarViewMode.DAY to "view_day"
            )
            // 2x2 grid, not a single 4-wide segmented row — a fixed-width sidebar on a phone-sized
            // screen doesn't leave room for four full-word segments without wrapping ("Month" -> "Mont/h").
            modes.chunked(2).forEach { rowModes ->
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    rowModes.forEachIndexed { index, (mode, labelKey) ->
                        SegmentedButton(
                            selected = viewMode == mode,
                            onClick = { stateManager.setViewMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = rowModes.size)
                        ) {
                            Text(languageManager.getString(labelKey), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(languageManager.getString("layers_title"), style = MaterialTheme.typography.labelLarge)
                Box {
                    IconButton(onClick = { addMenuExpanded = true }) {
                        Icon(Icons.Filled.Add, contentDescription = languageManager.getString("add_layer"))
                    }
                    DropdownMenu(expanded = addMenuExpanded, onDismissRequest = { addMenuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text(languageManager.getString("add_layer")) },
                            onClick = { addMenuExpanded = false; showAddLayerDialog = true }
                        )
                        DropdownMenuItem(
                            text = { Text(languageManager.getString("subscribe_calendar_menu_item")) },
                            onClick = { addMenuExpanded = false; showSubscribeDialog = true }
                        )
                    }
                }
            }

            // A bounded-height LazyColumn (not the enclosing verticalScroll Column's own children)
            // because sh.calvin.reorderable only operates on a real LazyListState — same constraint
            // and wiring as ToDoNodeTimelineEditable. Reordering is optimistic (local list mutates
            // during the drag for smooth feedback) and only persists once the gesture ends.
            var localLayers by remember(layers) { mutableStateOf(layers) }
            val layerListState = rememberLazyListState()
            val haptics = LocalHapticFeedback.current
            val reorderableState = rememberReorderableLazyListState(layerListState) { from, to ->
                localLayers = localLayers.toMutableList().apply {
                    val fromIndex = indexOfFirst { it.id == from.key }
                    val toIndex = indexOfFirst { it.id == to.key }
                    if (fromIndex >= 0 && toIndex >= 0) add(toIndex, removeAt(fromIndex))
                }
                haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
            }

            LazyColumn(
                state = layerListState,
                modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)
            ) {
                items(localLayers, key = { it.id }) { layer ->
                    ReorderableItem(reorderableState, key = layer.id) { isDragging ->
                        val elevation by animateDpAsState(if (isDragging) 6.dp else 0.dp, label = "layerDragElevation")
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .shadow(elevation)
                                .clickable { editingLayer = layer },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // The Main calendar's dot is a star instead of a circle — same shape its
                            // color swatches take in the edit dialog, so "Main" reads the same way
                            // wherever the calendar's color appears.
                            Box(
                                modifier = Modifier
                                    .size(if (layer.isDefault) 15.dp else 12.dp)
                                    .background(
                                        color = Color(layer.colorArgb.toInt()),
                                        shape = if (layer.isDefault) VoxSwatchShapes.Star else CircleShape
                                    )
                            )
                            Spacer(Modifier.width(8.dp))
                            if (layer.kind == CalendarLayerKind.SUBSCRIBED) {
                                Icon(
                                    Icons.Filled.CloudQueue,
                                    contentDescription = languageManager.getString("subscribed_calendar_badge"),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(
                                text = if (layer.isDefault) {
                                    "${layer.name} (${languageManager.getString("main_calendar_suffix")})"
                                } else {
                                    layer.name
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Checkbox(
                                checked = layer.visible,
                                onCheckedChange = { stateManager.updateLayer(layer.copy(visible = it)) }
                            )
                            Icon(
                                Icons.Filled.DragHandle,
                                contentDescription = languageManager.getString("reorder_calendar_handle"),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .size(18.dp)
                                    .longPressDraggableHandle(
                                        onDragStarted = {
                                            haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                        },
                                        onDragStopped = {
                                            haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                            stateManager.reorderLayers(localLayers.map { it.id })
                                        }
                                    )
                            )
                        }
                    }
                }
            }

            if (availableTags.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text(languageManager.getString("tags_title"), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    availableTags.forEach { tag ->
                        FilterChip(
                            selected = tag in selectedTags,
                            onClick = { stateManager.toggleTag(tag) },
                            label = { Text(tag, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
        }
    }

    if (showAddLayerDialog) {
        LayerEditDialog(
            layer = null,
            existingLayerColors = layers.map { it.colorArgb },
            onDismiss = { showAddLayerDialog = false },
            onSave = { name, color ->
                stateManager.addLayer(name, color)
                showAddLayerDialog = false
            },
            onDelete = null,
            onReminderOffsetsChange = null,
            onResyncNow = null,
            onDuplicateToOfflineCopy = null,
            onSetAsMain = null,
            animationsEnabled = settings.animationsEnabled,
            languageManager = languageManager
        )
    }

    if (showSubscribeDialog) {
        SubscribeCalendarDialog(
            existingLayerColors = layers.map { it.colorArgb },
            onDismiss = { showSubscribeDialog = false },
            onSubscribe = { name, color, url ->
                stateManager.addSubscribedLayer(name, color, url)
                showSubscribeDialog = false
            },
            languageManager = languageManager
        )
    }

    editingLayer?.let { editing ->
        // Re-resolve against the live list rather than using the snapshot captured when the dialog
        // opened — an in-dialog change that writes straight through (Main toggle, reminder offsets,
        // a resync updating lastSyncedAt) must be reflected right here, and the stale copy would
        // still report the pre-change values.
        val layer = layers.firstOrNull { it.id == editing.id }
        if (layer == null) {
            // Deleted out from under us — nothing left to edit, so close rather than render a ghost.
            LaunchedEffect(editing.id) { editingLayer = null }
            return@let
        }
        LayerEditDialog(
            layer = layer,
            existingLayerColors = layers.map { it.colorArgb },
            onDismiss = { editingLayer = null },
            onSave = { name, color ->
                stateManager.updateLayer(layer.copy(name = name, colorArgb = color))
                editingLayer = null
            },
            onDelete = if (!layer.isDefault) {
                {
                    deleteCandidate = layer
                    editingLayer = null
                }
            } else {
                null
            },
            onReminderOffsetsChange = { offsets -> stateManager.setLayerReminderOffsets(layer.id, offsets) },
            onResyncNow = if (layer.kind == CalendarLayerKind.SUBSCRIBED) {
                { stateManager.resyncSubscribedLayer(layer) }
            } else {
                null
            },
            onDuplicateToOfflineCopy = if (layer.kind == CalendarLayerKind.SUBSCRIBED) {
                { copyName -> stateManager.duplicateLayerToOfflineCopy(layer, copyName); editingLayer = null }
            } else {
                null
            },
            // Deliberately does NOT close the dialog — promoting to Main is an edit like any other
            // field here, and the switch/delete-button state updates in place via the live re-resolve
            // above.
            onSetAsMain = { stateManager.setMainLayer(layer.id) },
            animationsEnabled = settings.animationsEnabled,
            languageManager = languageManager
        )
    }

    deleteCandidate?.let { layer ->
        DeleteLayerConfirmDialog(
            layer = layer,
            onDismiss = { deleteCandidate = null },
            onDeleteAll = {
                stateManager.removeLayer(layer, LayerDeleteMode.DELETE_ALL_ENTRIES)
                deleteCandidate = null
            },
            onMoveToMain = {
                stateManager.removeLayer(layer, LayerDeleteMode.REASSIGN_TO_MAIN)
                deleteCandidate = null
            },
            languageManager = languageManager
        )
    }
}

@Composable
private fun DeleteLayerConfirmDialog(
    layer: CalendarLayer,
    onDismiss: () -> Unit,
    onDeleteAll: () -> Unit,
    onMoveToMain: () -> Unit,
    languageManager: com.voxapps.calendarapp.domain.localization.LanguageManager
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(String.format(languageManager.getString("delete_layer_confirm_title"), layer.name)) },
        text = { Text(languageManager.getString("delete_layer_confirm_desc")) },
        confirmButton = {
            TextButton(onClick = onDeleteAll) {
                Text(languageManager.getString("delete_layer_delete_all"), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onMoveToMain) { Text(languageManager.getString("delete_layer_move_to_main")) }
                TextButton(onClick = onDismiss) { Text(languageManager.getString("cancel")) }
            }
        }
    )
}

@Composable
private fun SubscribeCalendarDialog(
    existingLayerColors: List<Long>,
    onDismiss: () -> Unit,
    onSubscribe: (name: String, colorArgb: Long, url: String) -> Unit,
    languageManager: com.voxapps.calendarapp.domain.localization.LanguageManager
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var selectedColor by remember { mutableStateOf(CalendarLayerPalette.unusedOrRandomColor(existingLayerColors)) }
    val urlValid = remember(url) {
        val trimmed = url.trim()
        trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("webcal://", ignoreCase = true) ||
            trimmed.startsWith("webcals://", ignoreCase = true)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(languageManager.getString("subscribe_calendar_title")) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(languageManager.getString("layer_name")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(languageManager.getString("subscribe_calendar_url_label")) },
                    singleLine = true,
                    isError = url.isNotBlank() && !urlValid,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                if (url.isNotBlank() && !urlValid) {
                    Text(
                        languageManager.getString("subscribe_calendar_url_invalid"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                VoxColorSwatchPicker(
                    selectedColor = selectedColor,
                    onColorSelected = { selectedColor = it },
                    modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
                    customColorDialogTitle = languageManager.getString("custom_color_title"),
                    customColorUseLabel = languageManager.getString("use_color_button"),
                    customColorCancelLabel = languageManager.getString("cancel"),
                    customColorHueLabel = languageManager.getString("hue_label"),
                    customColorSaturationLabel = languageManager.getString("saturation_label"),
                    customColorBrightnessLabel = languageManager.getString("brightness_label")
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubscribe(name, selectedColor, url) },
                enabled = name.isNotBlank() && urlValid
            ) { Text(languageManager.getString("save")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(languageManager.getString("cancel")) }
        }
    )
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun LayerEditDialog(
    layer: CalendarLayer?,
    existingLayerColors: List<Long>,
    onDismiss: () -> Unit,
    onSave: (String, Long) -> Unit,
    onDelete: (() -> Unit)?,
    onReminderOffsetsChange: ((List<Int>) -> Unit)?,
    onResyncNow: (() -> Unit)?,
    onDuplicateToOfflineCopy: ((String) -> Unit)?,
    onSetAsMain: (() -> Unit)?,
    animationsEnabled: Boolean,
    languageManager: com.voxapps.calendarapp.domain.localization.LanguageManager
) {
    var showDuplicatePrompt by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(layer?.name ?: "") }
    var reminderOffsets by remember(layer?.id) {
        mutableStateOf(ReminderOffsetsCodec.decode(layer?.reminderOffsetsMinutes))
    }
    // New layers auto-suggest the first unused preset (or a random-but-distinct generated color once
    // all presets are taken) — mirrors vox-expenses' CategoriesSettingsTab, which assigns a category's
    // color via CategoryPalette.unusedOrRandomColor rather than making the user pick one. Editing an
    // existing layer still starts from its current color; the swatch grid below lets either case be
    // overridden manually, which vox-expenses doesn't offer but is worth keeping here since a layer's
    // color is far more visually persistent (shown on every event) than an expense category's.
    var selectedColor by remember {
        mutableStateOf(
            layer?.colorArgb ?: CalendarLayerPalette.unusedOrRandomColor(existingLayerColors)
        )
    }

    // Editing writes through as you go, so there's no Save/Cancel pair — which in turn means closing
    // the dialog (button OR tap-outside) must never be able to lose an edit. Color, the Main toggle
    // and reminders each commit the instant they change; the name commits on close instead of per
    // keystroke, which would be one DB write — and one full calendar recomposition — per character.
    // Creating a new calendar still needs an explicit Save: there's no row to write through to yet.
    val isEdit = layer != null
    fun closeCommittingName() {
        if (name.isNotBlank() && name != layer?.name) onSave(name, selectedColor)
        onDismiss()
    }
    // Main is shown as a star everywhere it appears — here on every swatch, and on the sidebar dot.
    val swatchShape = if (layer?.isDefault == true) VoxSwatchShapes.Star else CircleShape

    // Promotion feedback: a "Main" label floats up out of the star while the star itself pops.
    // Trigger is a counter rather than a Boolean so re-promoting replays the animation (two equal
    // Booleans wouldn't look like a state change). Both animations read the same progress value.
    var mainBurstTrigger by remember { mutableIntStateOf(0) }
    val burstProgress = remember { Animatable(0f) }
    LaunchedEffect(mainBurstTrigger) {
        if (mainBurstTrigger == 0) return@LaunchedEffect
        burstProgress.snapTo(0f)
        // tween(0) when animations are off jumps straight to 1f, so the label — gated on
        // 0 < progress < 1 — never renders at all, rather than flashing for a frame.
        burstProgress.animateTo(1f, animationSpec = if (animationsEnabled) tween(1800) else tween(0))
    }
    // Quick overshoot on the star: up to 1.35x by the first fifth, then settling back.
    val starScale = if (burstProgress.value <= 0f || burstProgress.value >= 1f) {
        1f
    } else {
        val p = burstProgress.value
        if (p < 0.2f) 1f + (p / 0.2f) * 0.35f else 1f + (1f - (p - 0.2f) / 0.8f) * 0.35f
    }

    AlertDialog(
        onDismissRequest = { if (isEdit) closeCommittingName() else onDismiss() },
        title = { Text(languageManager.getString(if (layer == null) "add_layer" else "edit_layer")) },
        text = {
            Column {
                // Boxed so the "Main" burst can float up out of the star and past the field's own
                // bounds — inside the trailingIcon slot it would be clipped to icon size.
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(languageManager.getString("layer_name")) },
                        singleLine = true,
                        // The Main toggle rides in the field's trailing slot rather than its own labelled
                        // row — a star that's filled when this calendar is Main, matching the sidebar dot
                        // and the swatch shape below. Absent entirely for a not-yet-created calendar
                        // (nothing to promote) and for a subscribed one (read-only, so it can never hold
                        // the entries that fall back to Main).
                        trailingIcon = if (onSetAsMain != null && layer != null && layer.kind != CalendarLayerKind.SUBSCRIBED) {
                            {
                                IconButton(
                                    onClick = {
                                        if (!layer.isDefault) {
                                            onSetAsMain()
                                            mainBurstTrigger++
                                        }
                                    },
                                    // Already-Main can't be switched off directly — exactly one calendar
                                    // always holds the flag, so it's only cleared by promoting another.
                                    enabled = !layer.isDefault
                                ) {
                                    Icon(
                                        imageVector = if (layer.isDefault) Icons.Filled.Star else Icons.Filled.StarBorder,
                                        contentDescription = languageManager.getString("set_as_main_calendar"),
                                        modifier = Modifier.scale(starScale),
                                        tint = if (layer.isDefault) {
                                            Color(layer.colorArgb.toInt())
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                            }
                        } else {
                            null
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    // Floating "Main" label rising out of the star and fading — makes the promotion
                    // legible, since the only other cue is a small outline-to-filled icon swap.
                    if (burstProgress.value > 0f && burstProgress.value < 1f) {
                        val progress = burstProgress.value
                        Text(
                            text = languageManager.getString("main_calendar_suffix"),
                            // A notch under the dialog's own title (headlineSmall), so it reads as a
                            // real announcement rather than a caption.
                            style = MaterialTheme.typography.titleLarge,
                            color = layer?.let { Color(it.colorArgb.toInt()) } ?: MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = 14.dp)
                                .offset(y = (-36 * progress).dp)
                                // Fades only over the back half, so it's fully legible while it travels.
                                .alpha(((1f - progress) * 2f).coerceAtMost(1f))
                        )
                    }
                }
                if (layer != null && layer.kind == CalendarLayerKind.SUBSCRIBED) {
                    Text(
                        languageManager.getString("set_as_main_subscribed_note"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                VoxColorSwatchPicker(
                    selectedColor = selectedColor,
                    // Live write while editing — a discrete tap, unlike the name's keystroke stream,
                    // so it's cheap to persist immediately and the sidebar recolors as you pick.
                    onColorSelected = {
                        selectedColor = it
                        if (isEdit && name.isNotBlank()) onSave(name, it)
                    },
                    shape = swatchShape,
                    modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
                    customColorDialogTitle = languageManager.getString("custom_color_title"),
                    customColorUseLabel = languageManager.getString("use_color_button"),
                    customColorCancelLabel = languageManager.getString("cancel"),
                    customColorHueLabel = languageManager.getString("hue_label"),
                    customColorSaturationLabel = languageManager.getString("saturation_label"),
                    customColorBrightnessLabel = languageManager.getString("brightness_label")
                )
                if (onReminderOffsetsChange != null) {
                    Spacer(Modifier.height(16.dp))
                    Text(languageManager.getString("calendar_reminders_title"), style = MaterialTheme.typography.labelLarge)
                    Text(
                        languageManager.getString("calendar_reminders_desc"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ReminderOffsetsPicker(
                        selected = reminderOffsets,
                        onToggle = { offset ->
                            reminderOffsets = if (offset in reminderOffsets) {
                                reminderOffsets - offset
                            } else {
                                reminderOffsets + offset
                            }
                            onReminderOffsetsChange(reminderOffsets)
                        },
                        languageManager = languageManager,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                if (layer != null && layer.kind == CalendarLayerKind.SUBSCRIBED) {
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = layer.subscriptionUrl.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = languageManager.getString("last_synced_label") + ": " + (
                            layer.lastSyncedAt?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) }
                                ?: languageManager.getString("last_synced_never")
                            ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    layer.lastSyncError?.let { error ->
                        Text(error, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                    Row {
                        onResyncNow?.let {
                            TextButton(onClick = it) { Text(languageManager.getString("sync_now_button")) }
                        }
                        onDuplicateToOfflineCopy?.let {
                            TextButton(onClick = { showDuplicatePrompt = true }) {
                                Text(languageManager.getString("duplicate_to_offline_copy_button"))
                            }
                        }
                    }
                }
                if (onDelete != null) {
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(4.dp))
                        Text(languageManager.getString("delete_layer"), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            if (isEdit) {
                // Not a Save — everything here has already been written. This just closes (flushing
                // any pending name edit), so there's no Cancel counterpart either.
                TextButton(onClick = ::closeCommittingName) {
                    Text(languageManager.getString("close"))
                }
            } else {
                TextButton(onClick = { if (name.isNotBlank()) onSave(name, selectedColor) }, enabled = name.isNotBlank()) {
                    Text(languageManager.getString("save"))
                }
            }
        },
        dismissButton = if (isEdit) {
            null
        } else {
            { TextButton(onClick = onDismiss) { Text(languageManager.getString("cancel")) } }
        }
    )

    if (showDuplicatePrompt && layer != null && onDuplicateToOfflineCopy != null) {
        var copyName by remember { mutableStateOf("${layer.name} (copy)") }
        AlertDialog(
            onDismissRequest = { showDuplicatePrompt = false },
            title = { Text(languageManager.getString("duplicate_to_offline_copy_button")) },
            text = {
                OutlinedTextField(
                    value = copyName,
                    onValueChange = { copyName = it },
                    label = { Text(languageManager.getString("duplicate_copy_name_label")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { onDuplicateToOfflineCopy(copyName); showDuplicatePrompt = false },
                    enabled = copyName.isNotBlank()
                ) { Text(languageManager.getString("save")) }
            },
            dismissButton = {
                TextButton(onClick = { showDuplicatePrompt = false }) { Text(languageManager.getString("cancel")) }
            }
        )
    }
}
