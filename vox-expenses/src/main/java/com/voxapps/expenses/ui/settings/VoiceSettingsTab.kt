package com.voxapps.expenses.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voxapps.expenses.data.Category
import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.expenses.state.ExpensesStateManager
import com.voxapps.expenses.ui.LocalLanguageManager

/** Defaults that only matter for expenses created headlessly via Commander's voice pipeline. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSettingsTab(
    settings: ExpensesSettings,
    categories: List<Category>,
    stateManager: ExpensesStateManager,
    modifier: Modifier = Modifier
) {
    val languageManager = LocalLanguageManager.current
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- Toast on voice save ---
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(languageManager.getString("voice_save_toast"), style = MaterialTheme.typography.bodyLarge)
                Text(
                    languageManager.getString("voice_save_toast_desc"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = settings.voiceSaveToastEnabled,
                onCheckedChange = { stateManager.setVoiceSaveToastEnabled(it) }
            )
        }

        HorizontalDivider()

        // --- Default category for voice/unresolved expenses ---
        Text(languageManager.getString("default_voice_category"), style = MaterialTheme.typography.labelLarge)
        Text(
            languageManager.getString("default_voice_category_desc"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        var expanded by remember { mutableStateOf(false) }
        val currentName = categories.firstOrNull { it.id == settings.defaultVoiceCategoryId }?.name
            ?: languageManager.getString("none")
        Column {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(currentName)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(languageManager.getString("none")) },
                    onClick = { stateManager.setDefaultVoiceCategoryId(null); expanded = false }
                )
                categories.forEach { cat ->
                    DropdownMenuItem(
                        text = { Text(cat.name) },
                        onClick = { stateManager.setDefaultVoiceCategoryId(cat.id); expanded = false }
                    )
                }
            }
        }

        HorizontalDivider()

        // --- Auto-create spoken categories ---
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
}
