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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.voxapps.calendarapp.data.preferences.CalendarSettings
import com.voxapps.calendarapp.state.CalendarStateManager
import com.voxapps.calendarapp.ui.LocalLanguageManager
import com.voxapps.design.settings.SettingsSectionCard
import com.voxapps.recordflow.ui.RecordFlowLevelCard
import com.voxapps.recordflow.ui.RecordFlowStrings

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
        SettingsSectionCard(languageManager.getString("zone_security")) {
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
        }

        RecordFlowLevelCard(
            support = CalendarSettings.SCAN_FLOW_SUPPORT,
            level = CalendarSettings.scanLevelOf(settings.scanLlmLevel),
            strings = RecordFlowStrings(
                title = languageManager.getString("scan_llm_level"),
                sendNothing = languageManager.getString("flow_send_nothing"),
                sendNothingDesc = languageManager.getString("flow_send_nothing_desc_calendar"),
                sendMissing = languageManager.getString("flow_send_missing"),
                sendMissingDesc = languageManager.getString("flow_send_missing_desc"),
                sendHead = languageManager.getString("flow_send_head"),
                sendHeadDesc = languageManager.getString("flow_send_head_desc"),
                sendEverything = languageManager.getString("flow_send_everything"),
                sendEverythingDesc = languageManager.getString("flow_send_everything_desc_calendar"),
                fillHead = languageManager.getString("calendar_fill_head"),
                cannotSuggest = languageManager.getString("flow_cannot_suggest")
            ),
            onLevelChange = { stateManager.setScanLlmLevel(it.name) }
        )

        SettingsSectionCard(languageManager.getString("zone_capture")) {
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

            // --- Field correction memory ---
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(languageManager.getString("field_correction_memory_label"), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        languageManager.getString("field_correction_memory_desc"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.fieldCorrectionMemoryEnabled,
                    onCheckedChange = { stateManager.setFieldCorrectionMemoryEnabled(it) }
                )
            }
            val correctionAlpha = if (settings.fieldCorrectionMemoryEnabled) 1f else 0.4f
            Column(modifier = Modifier.alpha(correctionAlpha), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(languageManager.getString("field_correction_speed_label"), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        CalendarSettings.CORRECTION_SPEED_INSTANT to "field_correction_speed_instant",
                        CalendarSettings.CORRECTION_SPEED_FAST to "field_correction_speed_fast",
                        CalendarSettings.CORRECTION_SPEED_MEDIUM to "field_correction_speed_medium",
                        CalendarSettings.CORRECTION_SPEED_SLOW to "field_correction_speed_slow"
                    ).forEach { (count, labelKey) ->
                        FilterChip(
                            selected = settings.fieldCorrectionThreshold == count,
                            onClick = { stateManager.setFieldCorrectionThreshold(count) },
                            label = { Text(languageManager.getString(labelKey)) }
                        )
                    }
                }
            }
        }

        SettingsSectionCard(languageManager.getString("zone_display")) {
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

        SettingsSectionCard(languageManager.getString("tutorial_section")) {
            Text(
                languageManager.getString("replay_tutorial_desc"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = { stateManager.replayTutorial() },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text(languageManager.getString("replay_tutorial")) }
        }
    }
}
