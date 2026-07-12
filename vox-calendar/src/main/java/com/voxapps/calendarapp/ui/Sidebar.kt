package com.voxapps.calendarapp.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voxapps.calendarapp.data.CalendarLayer
import com.voxapps.calendarapp.state.CalendarStateManager
import com.voxapps.calendarapp.state.CalendarViewMode

private val SIDEBAR_WIDTH = 200.dp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun Sidebar(
    viewMode: CalendarViewMode,
    layers: List<CalendarLayer>,
    availableTags: List<String>,
    selectedTags: Set<String>,
    stateManager: CalendarStateManager,
    modifier: Modifier = Modifier
) {
    val languageManager = LocalLanguageManager.current
    var showAddLayerDialog by remember { mutableStateOf(false) }
    var editingLayer by remember { mutableStateOf<CalendarLayer?>(null) }

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
                IconButton(onClick = { showAddLayerDialog = true }) {
                    Icon(Icons.Filled.Add, contentDescription = languageManager.getString("add_layer"))
                }
            }

            layers.forEach { layer ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { editingLayer = layer },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(color = Color(layer.colorArgb.toInt()), shape = CircleShape)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = layer.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Checkbox(
                        checked = layer.visible,
                        onCheckedChange = { stateManager.updateLayer(layer.copy(visible = it)) }
                    )
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
            languageManager = languageManager
        )
    }

    editingLayer?.let { layer ->
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
                    stateManager.removeLayer(layer)
                    editingLayer = null
                }
            } else {
                null
            },
            languageManager = languageManager
        )
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun LayerEditDialog(
    layer: CalendarLayer?,
    existingLayerColors: List<Long>,
    onDismiss: () -> Unit,
    onSave: (String, Long) -> Unit,
    onDelete: (() -> Unit)?,
    languageManager: com.voxapps.calendarapp.domain.localization.LanguageManager
) {
    var name by remember { mutableStateOf(layer?.name ?: "") }
    // New layers auto-suggest the first unused preset (or a random-but-distinct generated color once
    // all presets are taken) — mirrors vox-expenses' CategoriesSettingsTab, which assigns a category's
    // color via CategoryPalette.unusedOrRandomColor rather than making the user pick one. Editing an
    // existing layer still starts from its current color; the swatch grid below lets either case be
    // overridden manually, which vox-expenses doesn't offer but is worth keeping here since a layer's
    // color is far more visually persistent (shown on every event) than an expense category's.
    var selectedColor by remember {
        mutableStateOf(
            layer?.colorArgb ?: com.voxapps.calendarapp.data.CalendarLayerPalette.unusedOrRandomColor(existingLayerColors)
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(languageManager.getString(if (layer == null) "add_layer" else "edit_layer")) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(languageManager.getString("layer_name")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LayerColors.palette.forEach { color ->
                        val stored = LayerColors.toStored(color)
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(color = color, shape = CircleShape)
                                .then(
                                    if (stored == selectedColor) {
                                        Modifier.padding(2.dp)
                                    } else {
                                        Modifier
                                    }
                                )
                                .clickable { selectedColor = stored }
                        )
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
            TextButton(onClick = { if (name.isNotBlank()) onSave(name, selectedColor) }, enabled = name.isNotBlank()) {
                Text(languageManager.getString("save"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(languageManager.getString("cancel")) }
        }
    )
}
