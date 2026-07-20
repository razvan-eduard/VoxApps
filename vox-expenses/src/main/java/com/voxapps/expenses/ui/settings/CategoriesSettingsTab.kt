package com.voxapps.expenses.ui.settings

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.draw.alpha
import com.voxapps.design.rememberRequirementGate
import com.voxapps.expenses.data.Category
import com.voxapps.expenses.data.CategoryPalette
import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.expenses.state.ExpensesStateManager
import com.voxapps.expenses.ui.CategoryColors
import com.voxapps.expenses.ui.LocalLanguageManager
import com.voxapps.ipc.VoxAppsDiscovery

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
    val context = LocalContext.current
    var addingNew by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
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

    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(languageManager.getString("categories_settings_title"), style = MaterialTheme.typography.titleMedium)

        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(categories, key = { it.id }) { cat ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(CategoryColors.fromStored(cat.colorArgb))
                    )
                    Text(cat.name, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    IconButton(onClick = { pendingDeleteCategory = cat }) {
                        Icon(Icons.Filled.Delete, contentDescription = languageManager.getString("remove_category"))
                    }
                }
            }
        }

        if (addingNew) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text(languageManager.getString("category_name")) },
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (newName.isNotBlank()) {
                        val color = CategoryPalette.unusedOrRandomColor(categories.map { it.colorArgb })
                        stateManager.addCategory(newName.trim(), color)
                    }
                    newName = ""
                    addingNew = false
                }) { Text(languageManager.getString("save")) }
                TextButton(onClick = { addingNew = false; newName = "" }) {
                    Text(languageManager.getString("cancel"))
                }
            }
        } else {
            TextButton(onClick = { addingNew = true }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(languageManager.getString("add_category"))
            }
        }

        HorizontalDivider()

        // --- Auto-Merge Categories (manual trigger) ---
        Text(languageManager.getString("auto_merge_categories_button"), style = MaterialTheme.typography.labelLarge)
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

        HorizontalDivider()

        // --- Scheduled Auto-Merge ---
        Text(languageManager.getString("scheduled_merge_label"), style = MaterialTheme.typography.labelLarge)
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

        // --- Pending merge suggestion review ---
        if (resolvedEntries.isNotEmpty()) {
            HorizontalDivider()
            Text(languageManager.getString("category_merge_pending_title"), style = MaterialTheme.typography.labelLarge)

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
