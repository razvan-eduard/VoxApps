package com.voxapps.expenses.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voxapps.datahygiene.SyncLevel
import com.voxapps.design.settings.SettingsSectionCard
import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.design.picklist.Picklist
import com.voxapps.expenses.state.ExpensesStateManager
import com.voxapps.expenses.ui.LocalLanguageManager

/**
 * What this ledger volunteers to a paired device, and whether lists say where a record came from.
 * The device pairing itself — and which bank accounts are shared with whom — lives in Vox Hub; this
 * section only sets this app's own posture (see [ExpensesSettings.syncLevel]) and the provenance
 * display (see [ExpensesSettings.showSyncProvenance]).
 */
@Composable
fun DeviceSyncSettingsSection(settings: ExpensesSettings, stateManager: ExpensesStateManager) {
    val languageManager = LocalLanguageManager.current

    SettingsSectionCard(languageManager.getString("device_sync_section")) {
        Text(
            languageManager.getString("device_sync_level_desc"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        val currentLevel = ExpensesSettings.syncLevelOf(settings.syncLevel)
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

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                languageManager.getString("device_sync_show_origin"),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = settings.showSyncProvenance,
                onCheckedChange = { stateManager.setShowSyncProvenance(it) }
            )
        }
        Text(
            languageManager.getString("device_sync_show_origin_desc"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
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
