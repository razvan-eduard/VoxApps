package com.voxapps.notes.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voxapps.datahygiene.SyncLevel
import com.voxapps.design.picklist.Picklist
import com.voxapps.design.settings.SettingsSectionCard
import com.voxapps.notes.data.preferences.NotesSettings
import com.voxapps.notes.state.NotesStateManager
import com.voxapps.notes.ui.LocalLanguageManager

/**
 * What this app's notes volunteer to a paired device. The device pairing itself — and which
 * categories are shared with whom — lives in Vox Hub; this section only sets this app's own
 * posture (see [NotesSettings.syncLevel]).
 */
@Composable
fun DeviceSyncSettingsSection(settings: NotesSettings, stateManager: NotesStateManager) {
    val languageManager = LocalLanguageManager.current

    SettingsSectionCard(languageManager.getString("device_sync_section")) {
        Text(
            languageManager.getString("device_sync_level_desc"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        val currentLevel = NotesSettings.syncLevelOf(settings.syncLevel)
        Picklist(
            items = SyncLevel.entries.toList(),
            selected = currentLevel,
            itemLabel = { languageManager.getString(levelLabelKey(it)) },
            onSelect = { stateManager.setSyncLevel(it.name) },
            below = {
                Text(
                    languageManager.getString(levelDescriptionKey(currentLevel)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        )
    }
}

private fun levelLabelKey(level: SyncLevel): String = when (level) {
    SyncLevel.MANUAL -> "sync_level_manual"
    SyncLevel.SHARED -> "sync_level_shared"
    SyncLevel.ALL -> "sync_level_all"
}

private fun levelDescriptionKey(level: SyncLevel): String = when (level) {
    SyncLevel.MANUAL -> "sync_level_manual_desc"
    SyncLevel.SHARED -> "sync_level_shared_desc"
    SyncLevel.ALL -> "sync_level_all_desc"
}
