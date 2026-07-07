package com.voxapps.notes.ui.settings

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.voxapps.notes.R
import com.voxapps.notes.data.Category
import com.voxapps.notes.data.preferences.NotesSettings
import com.voxapps.notes.state.NotesStateManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsSettingsTab(
    settings: NotesSettings,
    categories: List<Category>,
    stateManager: NotesStateManager,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- Toast on voice save ---
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.voice_save_toast), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.voice_save_toast_desc),
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

        // --- Default category for voice notes ---
        Text(stringResource(R.string.default_voice_category), style = MaterialTheme.typography.labelLarge)
        Text(
            stringResource(R.string.default_voice_category_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        var expanded by remember { mutableStateOf(false) }
        val currentName = categories.firstOrNull { it.id == settings.defaultVoiceCategoryId }?.name
            ?: stringResource(R.string.none)
        Column {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(currentName)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.none)) },
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
                Text(stringResource(R.string.auto_create_voice_category), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.auto_create_voice_category_desc),
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
