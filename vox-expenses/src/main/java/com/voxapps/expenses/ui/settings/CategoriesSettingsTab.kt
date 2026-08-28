package com.voxapps.expenses.ui.settings

import com.voxapps.expenses.ExpensesApplication
import com.voxapps.onboarding.VoxHintKeys
import com.voxapps.onboarding.VoxHintDialog
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import com.voxapps.design.category.VoxCategoryEditCard
import com.voxapps.design.icon.VoxIconPickerDialog
import com.voxapps.expenses.ui.rememberCategoryFieldStrings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.StarBorder
import com.voxapps.design.color.VoxSwatchShapes
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.draw.alpha
import com.voxapps.design.rememberRequirementGate
import com.voxapps.expenses.data.Category
import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.expenses.state.ExpensesStateManager
import com.voxapps.expenses.ui.CategoryColors
import com.voxapps.expenses.ui.LocalLanguageManager
import com.voxapps.ipc.VoxAppsDiscovery
import com.voxapps.design.color.VoxColorPalette
import com.voxapps.design.settings.SettingsSectionCard

/**
 * Category CRUD, the Auto-Merge Categories trigger + schedule, and — unlike vox-notes, where the
 * equivalent auto-applies — a review section for the LLM's pending mapping. Merging expense
 * categories can reshuffle real financial data/reporting, so nothing merges until the user explicitly
 * approves specific entries here (mirrors vox-notes' NoteCleanupSettingsTab's review-section shape).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesSettingsTab(
    settings: ExpensesSettings,
    categories: List<Category>,
    stateManager: ExpensesStateManager,
    modifier: Modifier = Modifier
) {
    val languageManager = LocalLanguageManager.current
    VoxHintDialog(
        store = (LocalContext.current.applicationContext as ExpensesApplication).container.hintStore,
        hintKey = VoxHintKeys.CATEGORIES,
        title = languageManager.getString("hint_categories_title"),
        body = languageManager.getString("hint_categories_body"),
        okLabel = languageManager.getString("hint_ok"),
        dontShowAgainLabel = languageManager.getString("hint_dont_show_again")
    )
    val context = LocalContext.current
    var addingNew by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newIcon by remember { mutableStateOf<String?>(null) }
    /** The category open for editing, or null while the list is just a list. */
    var editing by remember { mutableStateOf<Category?>(null) }
    var editName by remember { mutableStateOf("") }
    var editIcon by remember { mutableStateOf<String?>(null) }
    var editColor by remember { mutableStateOf(0L) }
    var pendingDeleteCategory by remember { mutableStateOf<Category?>(null) }
    val commanderInstalled = remember { VoxAppsDiscovery.isCommanderInstalled(context) }
    val autoMergeGate = rememberRequirementGate(
        satisfied = commanderInstalled,
        requiredMessage = languageManager.getString("commander_required_message")
    ) {
        stateManager.requestCategoryAutoMerge(context, categories.map { it.name })
        Toast.makeText(context, languageManager.getString("auto_merge_categories_sent_toast"), Toast.LENGTH_SHORT).show()
    }

    val pendingMapping by stateManager.pendingCategoryMergeMapping.collectAsStateWithLifecycle(initialValue = emptyMap())
    // Resolved against the *current* categories — an entry drops out if either name no longer exists
    // (e.g. deleted since the suggestion arrived) rather than showing a stale/broken preview.
    val resolvedEntries = remember(pendingMapping, categories) {
        val names = categories.map { it.name }.toSet()
        pendingMapping.entries
            .filter { (old, canonical) -> old in names && canonical in names }
            .map { it.key to it.value }
    }
    var checkedEntries by remember(resolvedEntries) { mutableStateOf(resolvedEntries.indices.toSet()) }

    // The page scrolls, not the list inside it: a list that scrolls on its own takes the whole
    // height it is given and clips everything below it — which is where the button that adds a
    // category, and every section under it, happened to be.
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SettingsSectionCard(languageManager.getString("categories_settings_title")) {

            // The fallback first, then a rule, then the rest — a list where the starred one sits
            // wherever its position happens to put it says the star is a property of that row; above
            // a divider it says the row is what the others fall back to, which is what it is.
            val mainCategory = categories.firstOrNull { it.isDefault }
            val otherCategories = categories.filterNot { it.isDefault }
            (listOfNotNull(mainCategory) + otherCategories).forEach { cat ->
                    if (editing?.id == cat.id) {
                        // Edited where it is read, so the row you were looking at is the row that
                        // changes — a dialog over the list makes you find it again afterwards.
                        VoxCategoryEditCard(
                            name = editName,
                            onNameChange = { editName = it },
                            icon = editIcon,
                            onIconChange = { editIcon = it },
                            color = editColor,
                            onColorChange = { editColor = it },
                            strings = rememberCategoryFieldStrings(),
                            onSave = {
                                stateManager.updateCategory(
                                    cat.copy(name = editName.trim(), colorArgb = editColor, icon = editIcon)
                                )
                                editing = null
                            },
                            onCancel = { editing = null },
                            modifier = Modifier.padding(vertical = 6.dp),
                            // The editor's swatches wear what the list row wears: the fallback's
                            // star, everyone else's circle.
                            swatchShape = if (cat.isDefault) VoxSwatchShapes.Star else CircleShape
                        )
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    editing = cat
                                    editName = cat.name
                                    editIcon = cat.icon
                                    editColor = cat.colorArgb
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // The fallback wears a star instead of a dot and reads "(main)", the same way
                            // the calendar marks the layer entries fall back to.
                            Box(
                                modifier = Modifier
                                    .size(if (cat.isDefault) 17.dp else 14.dp)
                                    .clip(if (cat.isDefault) VoxSwatchShapes.Star else CircleShape)
                                    .background(CategoryColors.fromStored(cat.colorArgb))
                            )
                            // Shown, not edited here: the whole row opens the editor, where the icon
                            // sits beside the name and the colour it belongs with.
                            Box(
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .size(28.dp)
                                    .clip(CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    cat.icon ?: "＋",
                                    fontSize = if (cat.icon != null) 17.sp else 13.sp,
                                    color = if (cat.icon != null) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                            Text(
                                if (cat.isDefault) {
                                    "${cat.name} (${languageManager.getString("default_category_suffix")})"
                                } else {
                                    cat.name
                                },
                                modifier = Modifier.weight(1f).padding(start = 4.dp)
                            )
                            if (!cat.isDefault) {
                                IconButton(onClick = { stateManager.setDefaultCategory(cat.id) }) {
                                    Icon(
                                        Icons.Filled.StarBorder,
                                        contentDescription = languageManager.getString("set_default_category")
                                    )
                                }
                                IconButton(onClick = { pendingDeleteCategory = cat }) {
                                    Icon(Icons.Filled.Delete, contentDescription = languageManager.getString("remove_category"))
                                }
                            }
                        }
                        // Below the fallback, not between two ordinary rows: the line says everything
                        // under it falls back to what is above it, which a star on its own cannot.
                        if (cat.isDefault) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        }
                }
            }

            if (addingNew) {
                var newColor by remember { mutableStateOf(VoxColorPalette.unusedOrRandomColor(categories.map { it.colorArgb })) }
                VoxCategoryEditCard(
                    name = newName,
                    onNameChange = { newName = it },
                    icon = newIcon,
                    onIconChange = { newIcon = it },
                    color = newColor,
                    onColorChange = { newColor = it },
                    strings = rememberCategoryFieldStrings(),
                    onSave = {
                        stateManager.addCategory(newName.trim(), newColor, newIcon)
                        newName = ""
                        newIcon = null
                        addingNew = false
                    },
                    onCancel = { addingNew = false; newName = ""; newIcon = null }
                )
            } else {
                TextButton(onClick = { addingNew = true }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text(languageManager.getString("add_category"))
                }
            }

        }

        // --- Auto-Merge Categories (manual trigger) ---
        SettingsSectionCard(languageManager.getString("auto_merge_categories_button")) {
            Text(
                languageManager.getString("auto_merge_categories_desc"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (categories.size < 2) {
                Text(
                    languageManager.getString("auto_merge_categories_need_two"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                Button(
                    onClick = autoMergeGate.onClick,
                    modifier = Modifier.fillMaxWidth().alpha(autoMergeGate.alpha)
                ) {
                    Text(languageManager.getString("auto_merge_categories_button"))
                }
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
                    ExpensesSettings.INTERVAL_OFF to "scheduled_merge_off",
                    ExpensesSettings.INTERVAL_DAILY to "scheduled_merge_daily",
                    ExpensesSettings.INTERVAL_WEEKLY to "scheduled_merge_weekly",
                    ExpensesSettings.INTERVAL_MONTHLY to "scheduled_merge_monthly"
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

        // --- Pending merge suggestion review ---
        if (resolvedEntries.isNotEmpty()) {
            SettingsSectionCard(languageManager.getString("category_merge_pending_title")) {
                resolvedEntries.forEachIndexed { index, (oldName, canonicalName) ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = index in checkedEntries,
                                onCheckedChange = { checked ->
                                    checkedEntries = if (checked) checkedEntries + index else checkedEntries - index
                                }
                            )
                            Column(modifier = Modifier.padding(start = 4.dp)) {
                                Text(
                                    languageManager.getString("duplicate_label"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Text(oldName, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    languageManager.getString("keep_label"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                                Text(canonicalName, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { stateManager.dismissCategoryMerge() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(languageManager.getString("dismiss_all_button"))
                    }
                    Button(
                        onClick = {
                            val approved = checkedEntries.mapNotNull { resolvedEntries.getOrNull(it) }.toMap()
                            stateManager.approveCategoryMerge(approved)
                        },
                        enabled = checkedEntries.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(languageManager.getString("apply_selected_button"))
                    }
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
                TextButton(onClick = { pendingDeleteCategory = null }) { Text(languageManager.getString("cancel")) }
            }
        )
    }

}
