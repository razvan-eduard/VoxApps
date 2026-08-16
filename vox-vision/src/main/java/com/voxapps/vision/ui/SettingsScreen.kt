package com.voxapps.vision.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Tune
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
import com.voxapps.design.VoxDarkMode
import com.voxapps.design.settings.LogsSettingsTab
import com.voxapps.design.settings.LogsTabStrings
import com.voxapps.design.settings.SettingsSectionCard
import com.voxapps.design.settings.SettingsSectionHeader
import com.voxapps.design.settings.ThemeSettingsScreen
import com.voxapps.design.settings.ThemeSettingsStrings
import com.voxapps.design.toEnumOr
import com.voxapps.logging.Logger
import com.voxapps.logging.ui.LogViewerStrings
import com.voxapps.vision.di.VisionContainer
import com.voxapps.vision.data.preferences.VisionSettingsRepository
import kotlinx.coroutines.launch

private enum class SettingsPage { MENU, GENERAL, THEME, LOGS }

private val SENSITIVITY_LEVELS = listOf("low", "medium", "high")
private val PHOTO_DETAIL_LEVELS = listOf("low", "medium", "high")
private val STITCH_STRICTNESS_LEVELS = listOf("strict", "medium", "lazy")
private val AUTO_CAPTURE_DELAYS = listOf(0, 1, 2, 3, 5) // 0 = Manual

/**
 * Vision's settings: which OCR script/language zone is active (see
 * [com.voxapps.vision.domain.OcrModelRegistry]) — switching zones downloads the new zone's model and
 * deletes the previous one, only one ever sits on disk — how eagerly the live preview considers a
 * frame "framed" at all (see [com.voxapps.vision.ocr.DocumentCropper.DetectionSensitivity]), and how
 * long a framed document must hold before auto-capture fires (or Manual to disable it), among others.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: VisionContainer, onBack: () -> Unit) {
    val languageManager = LocalLanguageManager.current
    var page by remember { mutableStateOf(SettingsPage.MENU) }

    // System back mirrors the top-bar arrow: return to the previous page, or the main screen from MENU.
    BackHandler { if (page == SettingsPage.MENU) onBack() else page = SettingsPage.MENU }
    val zones = remember { container.ocrModelRegistry.zones() }
    val activeZone by container.settingsRepository.ocrZoneFlow.collectAsStateWithLifecycle(initialValue = null)
    val activeSensitivity by container.settingsRepository.autoTriggerSensitivityFlow.collectAsStateWithLifecycle(
        initialValue = VisionSettingsRepository.DEFAULT_SENSITIVITY
    )
    val activeAutoCaptureDelay by container.settingsRepository.autoCaptureDelaySecondsFlow.collectAsStateWithLifecycle(
        initialValue = VisionSettingsRepository.DEFAULT_AUTO_CAPTURE_DELAY
    )
    val debugLoggingEnabled by container.settingsRepository.debugLoggingEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
    val debugToastsEnabled by container.settingsRepository.debugToastsEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
    val sendPhotoToAi by container.settingsRepository.sendPhotoToAiFlow.collectAsStateWithLifecycle(initialValue = false)
    val photoDetailForAi by container.settingsRepository.photoDetailForAiFlow.collectAsStateWithLifecycle(
        initialValue = VisionSettingsRepository.DEFAULT_PHOTO_DETAIL
    )
    val themeDarkMode by container.settingsRepository.themeDarkModeFlow.collectAsStateWithLifecycle(
        initialValue = VisionSettingsRepository.THEME_SYSTEM
    )
    val themeColored by container.settingsRepository.themeColoredFlow.collectAsStateWithLifecycle(initialValue = true)
    val stitchStrictness by container.settingsRepository.stitchContinuityStrictnessFlow.collectAsStateWithLifecycle(
        initialValue = VisionSettingsRepository.DEFAULT_STITCH_STRICTNESS
    )
    var switching by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val title = when (page) {
        SettingsPage.MENU -> languageManager.getString("settings")
        SettingsPage.GENERAL -> languageManager.getString("general")
        SettingsPage.THEME -> languageManager.getString("theme_section")
        SettingsPage.LOGS -> languageManager.getString("logs_settings_title")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = { if (page == SettingsPage.MENU) onBack() else page = SettingsPage.MENU }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = languageManager.getString("back"))
                    }
                }
            )
        }
    ) { padding ->
        if (page == SettingsPage.MENU) {
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                SettingsSectionHeader(languageManager.getString("settings_section_general"))
                ListItem(
                    headlineContent = { Text(languageManager.getString("general")) },
                    leadingContent = { Icon(Icons.Filled.Tune, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().clickable { page = SettingsPage.GENERAL }
                )
                SettingsSectionHeader(languageManager.getString("settings_section_appearance"))
                ListItem(
                    headlineContent = { Text(languageManager.getString("theme_section")) },
                    leadingContent = { Icon(Icons.Filled.Palette, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().clickable { page = SettingsPage.THEME }
                )
                SettingsSectionHeader(languageManager.getString("settings_section_advanced"))
                ListItem(
                    headlineContent = { Text(languageManager.getString("logs_settings_title")) },
                    leadingContent = { Icon(Icons.Filled.BugReport, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().clickable { page = SettingsPage.LOGS }
                )
            }
            return@Scaffold
        }

        if (page == SettingsPage.THEME) {
            ThemeSettingsScreen(
                darkMode = themeDarkMode.toEnumOr(VoxDarkMode.SYSTEM),
                colored = themeColored,
                onDarkModeChange = { scope.launch { container.settingsRepository.setThemeDarkMode(it.name) } },
                onColoredChange = { scope.launch { container.settingsRepository.setThemeColored(it) } },
                strings = ThemeSettingsStrings(
                    darkModeSectionLabel = languageManager.getString("theme_section"),
                    themeSystemLabel = languageManager.getString("theme_system"),
                    themeLightLabel = languageManager.getString("theme_light"),
                    themeDarkLabel = languageManager.getString("theme_dark"),
                    coloredLabel = languageManager.getString("theme_colored"),
                    coloredDesc = languageManager.getString("theme_colored_desc")
                ),
                modifier = Modifier.fillMaxSize().padding(padding)
            )
            return@Scaffold
        }

        if (page == SettingsPage.LOGS) {
            LogsSettingsTab(
                enabled = debugLoggingEnabled,
                onEnabledChange = {
                    Logger.setEnabled(it)
                    scope.launch { container.settingsRepository.setDebugLoggingEnabled(it) }
                },
                toastsEnabled = debugToastsEnabled,
                onToastsEnabledChange = {
                    Logger.setToastsEnabled(it)
                    scope.launch { container.settingsRepository.setDebugToastsEnabled(it) }
                },
                strings = LogsTabStrings(
                    sectionLabel = languageManager.getString("logging_section"),
                    enabledLabel = languageManager.getString("debug_logging"),
                    enabledDesc = languageManager.getString("debug_logging_desc"),
                    toastsLabel = languageManager.getString("debug_toasts_label"),
                    viewer = LogViewerStrings(
                        sectionTitle = languageManager.getString("verbose_logging_section"),
                        clearLabel = languageManager.getString("clear_logs"),
                        copyLabel = languageManager.getString("copy_button"),
                        shareLabel = languageManager.getString("share_button"),
                        noLogsLabel = languageManager.getString("no_logs")
                    )
                ),
                shareSubject = "VoxVision Logs",
                modifier = Modifier.fillMaxSize().padding(padding)
            )
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SettingsSectionCard(languageManager.getString("ocr_language_zone")) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = zone == activeZone, onClick = null)
                            Text(zoneDisplayName(languageManager, zone), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }

            SettingsSectionCard(languageManager.getString("auto_trigger_sensitivity")) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SENSITIVITY_LEVELS.forEach { level ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = level == activeSensitivity,
                                    onClick = { scope.launch { container.settingsRepository.setAutoTriggerSensitivity(level) } }
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = level == activeSensitivity, onClick = null)
                            Text(languageManager.getString("sensitivity_$level"), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }

            SettingsSectionCard(languageManager.getString("auto_capture_delay")) {
                Text(
                    languageManager.getString("auto_capture_delay_desc"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AUTO_CAPTURE_DELAYS.forEach { delay ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = delay == activeAutoCaptureDelay,
                                    onClick = { scope.launch { container.settingsRepository.setAutoCaptureDelaySeconds(delay) } }
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = delay == activeAutoCaptureDelay, onClick = null)
                            Text(
                                languageManager.getString(
                                    if (delay == VisionSettingsRepository.AUTO_CAPTURE_MANUAL) "auto_capture_manual" else "auto_capture_${delay}s"
                                ),
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            }

            SettingsSectionCard(languageManager.getString("send_photo_to_ai")) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        languageManager.getString("send_photo_to_ai_desc"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = sendPhotoToAi,
                        onCheckedChange = { scope.launch { container.settingsRepository.setSendPhotoToAi(it) } }
                    )
                }

                // Detail level only exists to configure the switch above, so it stays in the same
                // card rather than becoming a section that vanishes whenever the switch is off.
                if (sendPhotoToAi) {
                    Text(languageManager.getString("photo_detail_for_ai"), style = MaterialTheme.typography.labelLarge)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        PHOTO_DETAIL_LEVELS.forEach { level ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = level == photoDetailForAi,
                                        onClick = { scope.launch { container.settingsRepository.setPhotoDetailForAi(level) } }
                                    ),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = level == photoDetailForAi, onClick = null)
                                Text(languageManager.getString("photo_detail_$level"), modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
            }

            SettingsSectionCard(languageManager.getString("stitch_continuity_strictness")) {
                Text(
                    languageManager.getString("stitch_continuity_strictness_desc"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    STITCH_STRICTNESS_LEVELS.forEach { level ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = level == stitchStrictness,
                                    onClick = { scope.launch { container.settingsRepository.setStitchContinuityStrictness(level) } }
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = level == stitchStrictness, onClick = null)
                            Text(languageManager.getString("stitch_strictness_$level"), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
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
