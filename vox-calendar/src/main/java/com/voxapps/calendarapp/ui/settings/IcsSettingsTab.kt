package com.voxapps.calendarapp.ui.settings

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxapps.design.color.VoxColorSwatchPicker
import com.voxapps.design.picklist.Picklist
import com.voxapps.design.picklist.PicklistFieldAnchor
import com.voxapps.calendarapp.data.CalendarLayer
import com.voxapps.calendarapp.data.CalendarLayerKind
import com.voxapps.calendarapp.data.CalendarRepository
import com.voxapps.calendarapp.domain.ics.IcsExportImportUtil
import com.voxapps.calendarapp.domain.ics.ParsedIcsFile
import com.voxapps.calendarapp.ui.LocalLanguageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.voxapps.design.color.VoxColorPalette

/** A parsed .ics file waiting for the user to pick its target calendar (see [ImportTargetDialog]). */
private data class PendingImport(val file: ParsedIcsFile, val fallbackName: String)

/**
 * Vox Calendar's own ICS import/export — a separate concern from Hub's JSON backup (Phase 6), for
 * interop with external calendar apps (Google/Apple/Thunderbird).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IcsSettingsTab(
    calendarRepository: CalendarRepository,
    modifier: Modifier = Modifier
) {
    val languageManager = LocalLanguageManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val layers by calendarRepository.layers.collectAsStateWithLifecycle(initialValue = emptyList())

    var pendingImport by remember { mutableStateOf<PendingImport?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/calendar")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            withContext(Dispatchers.IO) {
                val entries = calendarRepository.entriesSnapshot().filter { it.entry.listId == null }
                val layerSnapshot = calendarRepository.layersSnapshot()
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    IcsExportImportUtil.write(entries, layerSnapshot, out)
                }
            }
            Toast.makeText(context, languageManager.getString("ics_export_done"), Toast.LENGTH_SHORT).show()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val parsedFile = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    IcsExportImportUtil.readWithSuggestedName(input)
                }
            } ?: return@launch
            pendingImport = PendingImport(parsedFile, fallbackFileName(context, uri))
        }
    }

    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(languageManager.getString("ics_settings_title"), style = MaterialTheme.typography.titleMedium)
        Text(
            languageManager.getString("ics_settings_desc"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            onClick = { exportLauncher.launch("vox-calendar.ics") },
            modifier = Modifier.fillMaxWidth()
        ) { Text(languageManager.getString("ics_export_button")) }
        Button(
            onClick = { importLauncher.launch(arrayOf("text/calendar", "*/*")) },
            modifier = Modifier.fillMaxWidth()
        ) { Text(languageManager.getString("ics_import_button")) }
    }

    pendingImport?.let { pending ->
        ImportTargetDialog(
            suggestedName = pending.file.suggestedName ?: pending.fallbackName,
            existingLayers = layers.filter { it.kind == CalendarLayerKind.LOCAL },
            existingLayerColors = layers.map { it.colorArgb },
            onDismiss = { pendingImport = null },
            onConfirm = { targetLayerId, newLayerName, newLayerColor ->
                scope.launch {
                    val count = withContext(Dispatchers.IO) {
                        val resolvedLayerId = targetLayerId ?: calendarRepository.addLayer(
                            name = newLayerName ?: pending.fallbackName,
                            colorArgb = newLayerColor ?: VoxColorPalette.unusedOrRandomColor(layers.map { it.colorArgb }),
                            position = layers.size
                        )
                        IcsExportImportUtil.importEntriesIntoLayer(calendarRepository, pending.file.entries, resolvedLayerId)
                        pending.file.entries.size
                    }
                    Toast.makeText(
                        context,
                        String.format(languageManager.getString("ics_import_done"), count),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                pendingImport = null
            },
            languageManager = languageManager
        )
    }
}

/** Best-effort display name for the picked file, via the standard `OpenableColumns` content-resolver
 *  query (falls back to the URI's last path segment, then a generic default if even that's blank). */
private fun fallbackFileName(context: android.content.Context, uri: Uri): String {
    val fromColumn = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
    val name = fromColumn ?: uri.lastPathSegment ?: "Calendar"
    return name.substringBeforeLast(".ics").ifBlank { "Calendar" }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ImportTargetDialog(
    suggestedName: String,
    existingLayers: List<CalendarLayer>,
    existingLayerColors: List<Long>,
    onDismiss: () -> Unit,
    onConfirm: (targetLayerId: Long?, newLayerName: String?, newLayerColor: Long?) -> Unit,
    languageManager: com.voxapps.calendarapp.domain.localization.LanguageManager
) {
    var createNew by remember { mutableStateOf(true) }
    var newName by remember { mutableStateOf(suggestedName) }
    var newColor by remember { mutableStateOf(VoxColorPalette.unusedOrRandomColor(existingLayerColors)) }
    var selectedExisting by remember { mutableStateOf(existingLayers.firstOrNull()) }
    var dropdownExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(languageManager.getString("import_target_title")) },
        text = {
            Column {
                ImportTargetChoiceRow(createNew, onClick = { createNew = true }, label = languageManager.getString("import_target_new"))
                if (createNew) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text(languageManager.getString("import_target_new_name_label")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(start = 32.dp, top = 4.dp, bottom = 8.dp)
                    )
                    VoxColorSwatchPicker(
                        selectedColor = newColor,
                        onColorSelected = { newColor = it },
                        modifier = Modifier.padding(start = 32.dp, bottom = 8.dp),
                        customColorDialogTitle = languageManager.getString("custom_color_title"),
                        customColorUseLabel = languageManager.getString("use_color_button"),
                        customColorCancelLabel = languageManager.getString("cancel"),
                        customColorHueLabel = languageManager.getString("hue_label"),
                        customColorSaturationLabel = languageManager.getString("saturation_label"),
                        customColorBrightnessLabel = languageManager.getString("brightness_label")
                    )
                }
                if (existingLayers.isNotEmpty()) {
                    ImportTargetChoiceRow(!createNew, onClick = { createNew = false }, label = languageManager.getString("import_target_existing"))
                    if (!createNew) {
                        Picklist(
                            items = existingLayers,
                            selected = selectedExisting,
                            itemLabel = { it.name },
                            onSelect = { selectedExisting = it },
                            anchor = { value, onClick -> PicklistFieldAnchor(null, value, onClick) },
                            modifier = Modifier.padding(start = 32.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (createNew) {
                        onConfirm(null, newName.ifBlank { suggestedName }, newColor)
                    } else {
                        selectedExisting?.let { onConfirm(it.id, null, null) }
                    }
                },
                enabled = if (createNew) newName.isNotBlank() else selectedExisting != null
            ) { Text(languageManager.getString("ics_import_button")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(languageManager.getString("cancel")) }
        }
    )
}

@Composable
private fun ImportTargetChoiceRow(selected: Boolean, onClick: () -> Unit, label: String) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}
