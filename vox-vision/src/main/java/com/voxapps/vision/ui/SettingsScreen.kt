package com.voxapps.vision.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxapps.vision.di.VisionContainer
import com.voxapps.vision.data.preferences.VisionSettingsRepository
import kotlinx.coroutines.launch

private val SENSITIVITY_LEVELS = listOf("low", "medium", "high")
private val STABILITY_LEVELS = listOf("low", "medium", "high")

/**
 * Two picklists: which OCR script/language zone is active (see
 * [com.voxapps.vision.domain.OcrModelRegistry]) — switching zones downloads the new zone's model and
 * deletes the previous one, only one ever sits on disk — and how eagerly the live preview
 * auto-triggers a capture (see [com.voxapps.vision.ocr.DocumentCropper.DetectionSensitivity]).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: VisionContainer, onBack: () -> Unit) {
    val languageManager = LocalLanguageManager.current

    // System back mirrors the top-bar arrow: return to the main screen.
    BackHandler { onBack() }
    val zones = remember { container.ocrModelRegistry.zones() }
    val activeZone by container.settingsRepository.ocrZoneFlow.collectAsStateWithLifecycle(initialValue = null)
    val activeSensitivity by container.settingsRepository.autoTriggerSensitivityFlow.collectAsStateWithLifecycle(
        initialValue = VisionSettingsRepository.DEFAULT_SENSITIVITY
    )
    val activeStability by container.settingsRepository.autoTriggerStabilityFlow.collectAsStateWithLifecycle(
        initialValue = VisionSettingsRepository.DEFAULT_STABILITY
    )
    val debugLoggingEnabled by container.settingsRepository.debugLoggingEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
    var switching by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(languageManager.getString("settings")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = languageManager.getString("back"))
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text(languageManager.getString("ocr_language_zone"), style = MaterialTheme.typography.titleMedium)
            zones.forEach { zone ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = zone == activeZone,
                            onClick = {
                                if (zone == activeZone || switching != null) return@selectable
                                switching = zone
                                scope.launch {
                                    try {
                                        container.switchZone(zone)
                                    } finally {
                                        switching = null
                                    }
                                }
                            }
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = zone == activeZone, onClick = null)
                    Text(zoneDisplayName(languageManager, zone), modifier = Modifier.padding(start = 8.dp))
                }
            }

            Text(
                languageManager.getString("auto_trigger_sensitivity"),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 24.dp)
            )
            SENSITIVITY_LEVELS.forEach { level ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = level == activeSensitivity,
                            onClick = { scope.launch { container.settingsRepository.setAutoTriggerSensitivity(level) } }
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = level == activeSensitivity, onClick = null)
                    Text(languageManager.getString("sensitivity_$level"), modifier = Modifier.padding(start = 8.dp))
                }
            }

            Text(
                languageManager.getString("capture_speed"),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 24.dp)
            )
            STABILITY_LEVELS.forEach { level ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = level == activeStability,
                            onClick = { scope.launch { container.settingsRepository.setAutoTriggerStability(level) } }
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = level == activeStability, onClick = null)
                    Text(languageManager.getString("capture_speed_$level"), modifier = Modifier.padding(start = 8.dp))
                }
            }

            HorizontalDivider(modifier = Modifier.padding(top = 24.dp))

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(languageManager.getString("debug_logging"), style = MaterialTheme.typography.titleMedium)
                    Text(
                        languageManager.getString("debug_logging_desc"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = debugLoggingEnabled,
                    onCheckedChange = { scope.launch { container.settingsRepository.setDebugLoggingEnabled(it) } }
                )
            }
        }
    }

    val zoneBeingDownloaded = switching
    if (zoneBeingDownloaded != null) {
        Dialog(onDismissRequest = {}) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
                Text(
                    String.format(
                        languageManager.getString("downloading_zone_model"),
                        zoneDisplayName(languageManager, zoneBeingDownloaded)
                    ),
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        }
    }
}

private fun zoneDisplayName(languageManager: com.voxapps.vision.domain.localization.LanguageManager, zone: String): String {
    val key = "zone_$zone"
    val translated = languageManager.getString(key)
    return if (translated == key) zone.replaceFirstChar { it.uppercase() } else translated
}
