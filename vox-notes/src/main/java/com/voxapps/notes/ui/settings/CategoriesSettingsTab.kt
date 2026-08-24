package com.voxapps.notes.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.HorizontalDivider
import com.voxapps.design.color.VoxSwatchShapes
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import com.voxapps.design.rememberRequirementGate
import com.voxapps.ipc.VoxAppsDiscovery
import com.voxapps.design.picklist.Picklist
import com.voxapps.notes.data.Category
import com.voxapps.notes.data.preferences.NotesSettings
import com.voxapps.notes.domain.llm.SupportedLanguages
import com.voxapps.notes.state.NotesStateManager
import com.voxapps.design.category.VoxCategoryEditCard
import com.voxapps.design.color.VoxColorPalette
import com.voxapps.notes.ui.CategoryColors
import com.voxapps.notes.ui.rememberCategoryFieldStrings
import com.voxapps.design.settings.SettingsSectionCard
import com.voxapps.notes.ui.LocalLanguageManager

/**
 * Categories maintenance: the language that drives both the app's own UI and the Auto-Merge
 * Categories LLM prompt, a checkable list to choose which categories to include in a merge request
 * (defaults to all selected), the manual Auto-Merge trigger, and the scheduled-interval control.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesSettingsTab(
    settings: NotesSettings,
    categories: List<Category>,
    stateManager: NotesStateManager,
    modifier: Modifier = Modifier
) {
    val languageManager = LocalLanguageManager.current
    val context = LocalContext.current

    /** The category open for editing, or null while the list is just a list. */
    var editing by remember { mutableStateOf<Category?>(null) }
    var editName by remember { mutableStateOf("") }
    var editColor by remember { mutableStateOf(0L) }
    var addingNew by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newColor by remember { mutableStateOf(0L) }
    var pendingDeleteCategory by remember { mutableStateOf<Category?>(null) }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- Language (drives both UI copy and Auto-Merge prompt language) ---
        SettingsSectionCard(languageManager.getString("language_setting_label")) {
            Text(
                languageManager.getString("language_setting_desc"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Picklist(
                items = SupportedLanguages.ALL,
                selected = settings.language,
                itemLabel = { it.uppercase() },
                onSelect = { code ->
                    stateManager.setLanguage(code)
                    languageManager.loadLanguage(code)
                }
            )

        }

        // --- The categories themselves: named, coloured, removed ---
        //
        // Managed here as well as from the sidebar, because a setting that lists categories and
        // cannot change them sends you looking for the place that can.
        SettingsSectionCard(languageManager.getString("categories_settings_title")) {
            // The fallback first, above a line: a star wherever the sort happens to put it says the
            // star is a property of that row, while above the line it says the rest fall back to it.
            val fallback = categories.firstOrNull { it.isDefault }
            (listOfNotNull(fallback) + categories.filterNot { it.isDefault }).forEach { cat ->
                if (editing?.id == cat.id) {
                    // Edited in place, so the row you were looking at is the row that changes.
                    VoxCategoryEditCard(
                        name = editName,
                        onNameChange = { editName = it },
                        color = editColor,
                        onColorChange = { editColor = it },
                        strings = rememberCategoryFieldStrings(),
                        onSave = {
                            stateManager.updateCategory(cat.copy(name = editName.trim(), colorArgb = editColor))
                            editing = null
                        },
                        onCancel = { editing = null },
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                editing = cat
                                editName = cat.name
                                editColor = cat.colorArgb
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(if (cat.isDefault) 17.dp else 14.dp)
                                .clip(if (cat.isDefault) VoxSwatchShapes.Star else CircleShape)
                                .background(CategoryColors.fromStored(cat.colorArgb))
                        )
                        Text(
                            if (cat.isDefault) {
                                "${cat.name} (${languageManager.getString("default_category_suffix")})"
                            } else {
                                cat.name
                            },
                            modifier = Modifier.weight(1f).padding(start = 8.dp)
                        )
                        if (!cat.isDefault) {
                            IconButton(onClick = { stateManager.setDefaultCategory(cat.id) }) {
                                Icon(
                                    Icons.Filled.StarBorder,
                                    contentDescription = languageManager.getString("set_default_category")
                                )
                            }
                            IconButton(onClick = { pendingDeleteCategory = cat }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = languageManager.getString("delete_category_title")
                                )
                            }
                        }
                    }
                    if (cat.isDefault) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
            }

            if (addingNew) {
                VoxCategoryEditCard(
                    name = newName,
                    onNameChange = { newName = it },
                    color = newColor,
                    onColorChange = { newColor = it },
                    strings = rememberCategoryFieldStrings(),
                    onSave = {
                        stateManager.addCategory(newName.trim(), newColor)
                        newName = ""
                        addingNew = false
                    },
                    onCancel = { addingNew = false; newName = "" }
                )
            } else {
                TextButton(onClick = {
                    newName = ""
                    newColor = VoxColorPalette.unusedOrRandomColor(categories.map { it.colorArgb })
                    addingNew = true
                }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text(languageManager.getString("add_category"), modifier = Modifier.padding(start = 4.dp))
                }
            }
        }

        // --- Voice notes: which category they land in ---
        //
        // These belong beside the categories they choose between rather than beside notifications,
        // which is where they used to live and how they came to be dropped when that screen was
        // replaced by the shared notification card.
        SettingsSectionCard(languageManager.getString("default_voice_category")) {
            Text(
                languageManager.getString("default_voice_category_desc"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Picklist(
                items = categories,
                selected = categories.firstOrNull { it.id == settings.defaultVoiceCategoryId },
                itemLabel = { it.name },
                onSelect = { stateManager.setDefaultVoiceCategoryId(it.id) },
                noneLabel = languageManager.getString("none"),
                onNoneSelected = { stateManager.setDefaultVoiceCategoryId(null) }
            )

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(languageManager.getString("auto_create_voice_category"), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        languageManager.getString("auto_create_voice_category_desc"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.autoCreateVoiceCategory,
                    onCheckedChange = { stateManager.setAutoCreateVoiceCategory(it) }
                )
            }

        }

        // --- Auto-Merge Categories (manual trigger, selectable list) ---
        SettingsSectionCard(languageManager.getString("auto_merge_categories_button")) {
            Text(
                languageManager.getString("auto_merge_categories_desc"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Defaults to "all selected"; re-defaults whenever the category list itself changes (added,
            // removed, or merged away) rather than trying to preserve a sticky selection across that.
            var selectedIds by remember(categories) { mutableStateOf(categories.map { it.id }.toSet()) }

            if (categories.size < 2) {
                Text(
                    languageManager.getString("auto_merge_categories_need_two"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable {
                        selectedIds = if (selectedIds.size == categories.size) emptySet() else categories.map { it.id }.toSet()
                    },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = selectedIds.size == categories.size,
                        onCheckedChange = { checked ->
                            selectedIds = if (checked) categories.map { it.id }.toSet() else emptySet()
                        }
                    )
                    Text(languageManager.getString("select_all"))
                }

                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                    items(categories, key = { it.id }) { cat ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItem()
                                .clickable {
                                    selectedIds = if (cat.id in selectedIds) selectedIds - cat.id else selectedIds + cat.id
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = cat.id in selectedIds,
                                onCheckedChange = {
                                    selectedIds = if (it) selectedIds + cat.id else selectedIds - cat.id
                                }
                            )
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .clip(CircleShape)
                                    .background(CategoryColors.fromStored(cat.colorArgb))
                            )
                            Text(cat.name, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }

            val commanderInstalled = remember { VoxAppsDiscovery.isCommanderInstalled(context) }
            val autoMergeGate = rememberRequirementGate(
                satisfied = commanderInstalled,
                requiredMessage = languageManager.getString("commander_required_message")
            ) {
                val names = categories.filter { it.id in selectedIds }.map { it.name }
                stateManager.requestCategoryAutoMerge(context, names)
                Toast.makeText(context, languageManager.getString("auto_merge_categories_sent_toast"), Toast.LENGTH_SHORT).show()
            }
            Button(
                onClick = autoMergeGate.onClick,
                enabled = selectedIds.size >= 2,
                modifier = Modifier.fillMaxWidth().alpha(autoMergeGate.alpha)
            ) {
                Text(languageManager.getString("auto_merge_categories_button"))
            }

        }

        // --- Scheduled Auto-Merge ---
        SettingsSectionCard(languageManager.getString("scheduled_merge_label")) {
            Text(
                languageManager.getString("scheduled_merge_desc"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                val options = listOf(
                    NotesSettings.INTERVAL_OFF to "scheduled_merge_off",
                    NotesSettings.INTERVAL_DAILY to "scheduled_merge_daily",
                    NotesSettings.INTERVAL_WEEKLY to "scheduled_merge_weekly",
                    NotesSettings.INTERVAL_MONTHLY to "scheduled_merge_monthly"
                )
                options.forEach { (interval, labelKey) ->
                    FilterChip(
                        selected = settings.scheduledMergeInterval == interval,
                        onClick = { stateManager.setScheduledMergeInterval(context, interval) },
                        label = { Text(languageManager.getString(labelKey)) }
                    )
                }
            }
        }
    }

    pendingDeleteCategory?.let { category ->
        AlertDialog(
            onDismissRequest = { pendingDeleteCategory = null },
            title = { Text(languageManager.getString("delete_category_title")) },
            text = { Text(languageManager.getString("delete_category_message")) },
            confirmButton = {
                TextButton(onClick = {
                    stateManager.removeCategory(category)
                    pendingDeleteCategory = null
                }) { Text(languageManager.getString("delete")) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteCategory = null }) {
                    Text(languageManager.getString("cancel"))
                }
            }
        )
    }
}
