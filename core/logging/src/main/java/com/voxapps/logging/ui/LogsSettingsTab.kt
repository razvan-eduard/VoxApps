package com.voxapps.logging.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxapps.logging.Logger

data class LogsTabStrings(
    val enabledLabel: String,
    val enabledDesc: String,
    val toastsLabel: String,
    val viewer: LogViewerStrings
)

/**
 * The shared "Logs" settings tab every app renders identically: an on/off switch for debug
 * logging, a toasts switch (only meaningful while logging is on), and — while on — the
 * [LogViewerCard] ring-buffer viewer. State (the two booleans) stays per-app, backed by that app's
 * own DataStore-backed settings, same as every other toggle; only this composable is shared.
 */
@Composable
fun LogsSettingsTab(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    toastsEnabled: Boolean,
    onToastsEnabledChange: (Boolean) -> Unit,
    strings: LogsTabStrings,
    shareSubject: String,
    modifier: Modifier = Modifier
) {
    val logs by Logger.verboseLogs.collectAsStateWithLifecycle()

    // Mirrors Commander's own logging tab: turning the main switch off silences toasts too,
    // rather than leaving a stale "toasts on" flag with nothing left to gate it.
    LaunchedEffect(enabled) {
        if (!enabled && toastsEnabled) onToastsEnabledChange(false)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(strings.enabledLabel, style = MaterialTheme.typography.bodyLarge)
                Text(
                    strings.enabledDesc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(strings.toastsLabel, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(checked = toastsEnabled, onCheckedChange = onToastsEnabledChange, enabled = enabled)
        }

        if (enabled) {
            LogViewerCard(logs = logs, strings = strings.viewer, shareSubject = shareSubject)
        }
    }
}
