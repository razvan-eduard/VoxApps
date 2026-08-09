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

        HorizontalDivider()

        // --- Show event details (description) in the home-screen widget ---
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(languageManager.getString("widget_show_event_details"), style = MaterialTheme.typography.bodyLarge)
                Text(
                    languageManager.getString("widget_show_event_details_desc"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = settings.showEventDetailsInWidget,
                onCheckedChange = { stateManager.setShowEventDetailsInWidget(it) }
            )
        }

        HorizontalDivider()

        // --- Whether a to-do item's due date makes it show up on the standard calendar grid. The
        // underlying CalendarEntry row (and its reminder) always exists either way — this only
        // filters it out of Month/Week/Day/Year rendering when off. ---
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(languageManager.getString("todo_bleed_to_calendar"), style = MaterialTheme.typography.bodyLarge)
                Text(
                    languageManager.getString("todo_bleed_to_calendar_desc"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = settings.todoBleedToCalendar,
                onCheckedChange = { stateManager.setTodoBleedToCalendar(it) }
            )
        }
    }
}
