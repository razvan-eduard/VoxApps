package com.voxapps.notes.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.voxapps.notes.R
import com.voxapps.notes.data.preferences.NotesSettings
import com.voxapps.notes.state.NotesStateManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneralSettingsTab(
    settings: NotesSettings,
    stateManager: NotesStateManager,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = stringResource(R.string.general), style = MaterialTheme.typography.titleMedium)

        // --- Require fingerprint ---
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.require_fingerprint), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.require_fingerprint_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = settings.isBiometricRequired,
                onCheckedChange = { stateManager.setBiometricRequired(it) }
            )
        }

        HorizontalDivider()

        // --- Session timeout ---
        Text(stringResource(R.string.session_timeout), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            val options = listOf(
                NotesSettings.TIMEOUT_30M to R.string.timeout_30m,
                NotesSettings.TIMEOUT_1H to R.string.timeout_1h,
                NotesSettings.TIMEOUT_1D to R.string.timeout_1d,
                NotesSettings.TIMEOUT_UNLIMITED to R.string.timeout_unlimited
            )
            options.forEach { (minutes, labelRes) ->
                FilterChip(
                    selected = settings.sessionTimeoutMinutes == minutes,
                    onClick = { stateManager.setSessionTimeoutMinutes(minutes) },
                    label = { Text(stringResource(labelRes)) }
                )
            }
        }
    }
}
