package com.voxapps.hub.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxapps.hub.data.preferences.HubSettings
import com.voxapps.hub.data.preferences.HubSettingsRepository
import kotlinx.coroutines.launch

/** Hub's sole settings screen — just the shared theme controls (mirrors the satellites' GeneralSettingsTab). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HubSettingsScreen(
    settingsRepo: HubSettingsRepository,
    onBack: () -> Unit
) {
    val languageManager = LocalLanguageManager.current
    val settings by settingsRepo.settingsFlow.collectAsStateWithLifecycle(initialValue = HubSettings())
    val scope = rememberCoroutineScope()

    // Without this, the system back gesture/button falls through to the Activity's default
    // behavior (no back stack, single Activity) and closes the app instead of returning to the
    // main screen — matches the same fix already applied in vox-notes'/vox-expenses' SettingsScreen.
    BackHandler(onBack = onBack)

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
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(languageManager.getString("theme_section"), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val modes = listOf(
                    HubSettings.THEME_SYSTEM to "theme_system",
                    HubSettings.THEME_LIGHT to "theme_light",
                    HubSettings.THEME_DARK to "theme_dark"
                )
                modes.forEach { (mode, labelKey) ->
                    FilterChip(
                        selected = settings.themeDarkMode == mode,
                        onClick = { scope.launch { settingsRepo.setThemeDarkMode(mode) } },
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
                Switch(
                    checked = settings.themeColored,
                    onCheckedChange = { scope.launch { settingsRepo.setThemeColored(it) } }
                )
            }
        }
    }
}
