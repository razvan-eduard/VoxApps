package com.voxapps.backup.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voxapps.backup.VoxImportMode

/**
 * The shared Backup & Restore settings section for every app (and Hub itself). Pure
 * values-in/callbacks-out (no repo/SAF/zip logic inside the card itself) — the host app owns its
 * own `CreateDocument`/`OpenDocument` launchers and, inside their callbacks, calls its own
 * `*ExportImportHandler` plus `VoxLocalBackupFile.write`/`readForDomain` (see
 * `core/location/.../VoxLocationSettingsCard.kt` for the identical convention this mirrors).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoxBackupSettingsCard(
    state: VoxBackupUiState,
    features: VoxBackupCardFeatures = VoxBackupCardFeatures(),
    onIncludeSettingsChange: (Boolean) -> Unit,
    onIncludeDataChange: (Boolean) -> Unit,
    onIncludeApiKeysChange: (Boolean) -> Unit,
    onIncludeAttachmentsChange: (Boolean) -> Unit,
    onImportModeChange: (VoxImportMode) -> Unit,
    onBackupNowClick: () -> Unit,
    onRestoreClick: () -> Unit,
    strings: VoxBackupStrings = VoxBackupStrings(),
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(strings.sectionTitle, style = MaterialTheme.typography.titleMedium)

            if (features.showSettingsToggle) {
                ToggleRow(
                    label = strings.includeSettingsLabel,
                    checked = state.includeSettings,
                    onCheckedChange = onIncludeSettingsChange
                )
            }
            if (features.showDataToggle) {
                ToggleRow(
                    label = strings.includeDataLabel,
                    checked = state.includeData,
                    onCheckedChange = onIncludeDataChange
                )
            }
            if (features.showApiKeysToggle) {
                ToggleRow(
                    label = strings.includeApiKeysLabel,
                    description = strings.includeApiKeysDesc,
                    checked = state.includeApiKeys,
                    onCheckedChange = onIncludeApiKeysChange
                )
            }
            if (features.showAttachmentsToggle) {
                ToggleRow(
                    label = strings.includeAttachmentsLabel,
                    checked = state.includeAttachments,
                    onCheckedChange = onIncludeAttachmentsChange
                )
            }

            if (features.showImportModeSelector) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(strings.importModeLabel, style = MaterialTheme.typography.labelMedium)
                    Text(
                        strings.importModeDesc,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        VoxImportMode.entries.forEach { mode ->
                            FilterChip(
                                selected = state.importMode == mode,
                                onClick = { onImportModeChange(mode) },
                                label = {
                                    Text(
                                        strings.labelFor(mode),
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            )
                        }
                    }
                    Text(
                        strings.caveatFor(state.importMode),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state.importMode == VoxImportMode.MERGE) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onBackupNowClick, enabled = !state.isBusy, modifier = Modifier.weight(1f)) {
                    if (state.isBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(strings.backupNowButton)
                    }
                }
                if (features.showRestoreButton) {
                    OutlinedButton(onClick = onRestoreClick, enabled = !state.isBusy, modifier = Modifier.weight(1f)) {
                        Text(strings.restoreButton)
                    }
                }
            }

            state.lastResultMessage?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            description?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
