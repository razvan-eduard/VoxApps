package com.voxapps.calendarapp.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voxapps.calendarapp.data.preferences.CalendarSettings
import com.voxapps.calendarapp.state.CalendarStateManager
import com.voxapps.calendarapp.ui.LocalLanguageManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneralSettingsTab(
    settings: CalendarSettings,
    stateManager: CalendarStateManager,
    modifier: Modifier = Modifier
) {
    val languageManager = LocalLanguageManager.current
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = languageManager.getString("general"), style = MaterialTheme.typography.titleMedium)

        // --- Theme (mirrors vox-commander's General settings) ---
        Text(languageManager.getString("theme_section"), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val themeModes = listOf(
                CalendarSettings.THEME_SYSTEM to "theme_system",
                CalendarSettings.THEME_LIGHT to "theme_light",
                CalendarSettings.THEME_DARK to "theme_dark"
            )
            themeModes.forEach { (mode, labelKey) ->
                FilterChip(
                    selected = settings.themeDarkMode == mode,
                    onClick = { stateManager.setThemeDarkMode(mode) },
                    label = { Text(languageManager.getString(labelKey)) }
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(languageManager.getString("theme_colored"), style = MaterialTheme.typography.bodyMedium)
                Text(
                    languageManager.getString("theme_colored_desc"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = settings.themeColored, onCheckedChange = { stateManager.setThemeColored(it) })
        }

        HorizontalDivider()

        // --- Require fingerprint ---
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(languageManager.getString("require_fingerprint"), style = MaterialTheme.typography.bodyLarge)
                Text(
                    languageManager.getString("require_fingerprint_desc"),
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
        Text(languageManager.getString("session_timeout"), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            val options = listOf(
                CalendarSettings.TIMEOUT_30M to "timeout_30m",
                CalendarSettings.TIMEOUT_1H to "timeout_1h",
                CalendarSettings.TIMEOUT_1D to "timeout_1d",
                CalendarSettings.TIMEOUT_UNLIMITED to "timeout_unlimited"
            )
            options.forEach { (minutes, labelKey) ->
                FilterChip(
                    selected = settings.sessionTimeoutMinutes == minutes,
                    onClick = { stateManager.setSessionTimeoutMinutes(minutes) },
                    label = { Text(languageManager.getString(labelKey)) }
                )
            }
        }

        HorizontalDivider()

        // --- Attach photo to AI on scan (opt-in; costs real LLM tokens on top of free OCR text,
        // and only takes effect when Vision's own "send photo to AI" setting also provided one). ---
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(languageManager.getString("attach_photo_on_scan"), style = MaterialTheme.typography.bodyLarge)
                Text(
                    languageManager.getString("attach_photo_on_scan_desc"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = settings.attachPhotoOnScan,
                onCheckedChange = { stateManager.setAttachPhotoOnScan(it) }
            )
        }
    }
}
