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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import com.voxapps.notes.data.Category
import com.voxapps.notes.data.preferences.NotesSettings
import com.voxapps.notes.domain.llm.SupportedLanguages
import com.voxapps.notes.state.NotesStateManager
import com.voxapps.notes.ui.CategoryColors
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

    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- Language (drives both UI copy and Auto-Merge prompt language) ---
        Text(languageManager.getString("language_setting_label"), style = MaterialTheme.typography.labelLarge)
        Text(
            languageManager.getString("language_setting_desc"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        var languageExpanded by remember { mutableStateOf(false) }
        Column {
            OutlinedButton(onClick = { languageExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(settings.language.uppercase())
            }
            DropdownMenu(expanded = languageExpanded, onDismissRequest = { languageExpanded = false }) {
                SupportedLanguages.ALL.forEach { code ->
                    DropdownMenuItem(
                        text = { Text(code.uppercase()) },
                        onClick = {
                            stateManager.setLanguage(code)
                            languageManager.loadLanguage(code)
                            languageExpanded = false
                        }
                    )
                }
            }
        }

        HorizontalDivider()

        // --- Auto-Merge Categories (manual trigger, selectable list) ---
        Text(languageManager.getString("auto_merge_categories_button"), style = MaterialTheme.typography.labelLarge)
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

        Button(
            onClick = {
                val names = categories.filter { it.id in selectedIds }.map { it.name }
                stateManager.requestCategoryAutoMerge(context, names)
                Toast.makeText(context, languageManager.getString("auto_merge_categories_sent_toast"), Toast.LENGTH_SHORT).show()
            },
            enabled = selectedIds.size >= 2,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(languageManager.getString("auto_merge_categories_button"))
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
