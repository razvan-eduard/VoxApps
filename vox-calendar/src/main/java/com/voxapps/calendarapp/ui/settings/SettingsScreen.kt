package com.voxapps.calendarapp.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxapps.calendarapp.data.CalendarRepository
import com.voxapps.calendarapp.data.preferences.CalendarSettings
import com.voxapps.calendarapp.data.preferences.CalendarSettingsRepository
import com.voxapps.calendarapp.state.CalendarStateManager
import com.voxapps.calendarapp.ui.LocalLanguageManager
import com.voxapps.logging.ui.LogViewerStrings
import com.voxapps.logging.ui.LogsSettingsTab
import com.voxapps.logging.ui.LogsTabStrings

private enum class SettingsPage { MENU, GENERAL, ICS, LOGS }

/** Settings shell (mirrors vox-expenses' SettingsScreen menu/subpage shape). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    stateManager: CalendarStateManager,
    settingsRepo: CalendarSettingsRepository,
    calendarRepository: CalendarRepository,
    onBack: () -> Unit
) {
    val languageManager = LocalLanguageManager.current
    val settings by settingsRepo.settingsFlow.collectAsStateWithLifecycle(initialValue = CalendarSettings())
    var page by remember { mutableStateOf(SettingsPage.MENU) }

    BackHandler { if (page == SettingsPage.MENU) onBack() else page = SettingsPage.MENU }

    val title = when (page) {
        SettingsPage.MENU -> languageManager.getString("settings")
        SettingsPage.GENERAL -> languageManager.getString("general")
        SettingsPage.ICS -> languageManager.getString("ics_settings_title")
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
        when (page) {
            SettingsPage.MENU -> {
                Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                    ListItem(
                        headlineContent = { Text(languageManager.getString("general")) },
                        leadingContent = { Icon(Icons.Filled.Tune, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth().clickable { page = SettingsPage.GENERAL }
                    )
                    ListItem(
                        headlineContent = { Text(languageManager.getString("ics_settings_title")) },
                        leadingContent = { Icon(Icons.Filled.CalendarMonth, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth().clickable { page = SettingsPage.ICS }
                    )
                    ListItem(
                        headlineContent = { Text(languageManager.getString("logs_settings_title")) },
                        leadingContent = { Icon(Icons.Filled.BugReport, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth().clickable { page = SettingsPage.LOGS }
                    )
                }
            }
            SettingsPage.GENERAL -> GeneralSettingsTab(
                settings = settings,
                stateManager = stateManager,
                modifier = Modifier.fillMaxSize().padding(padding)
            )
            SettingsPage.ICS -> IcsSettingsTab(
                calendarRepository = calendarRepository,
                modifier = Modifier.fillMaxSize().padding(padding)
            )
            SettingsPage.LOGS -> LogsSettingsTab(
                enabled = settings.debugLoggingEnabled,
                onEnabledChange = { stateManager.setDebugLoggingEnabled(it) },
                toastsEnabled = settings.debugToastsEnabled,
                onToastsEnabledChange = { stateManager.setDebugToastsEnabled(it) },
                strings = LogsTabStrings(
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
                shareSubject = "VoxCalendar Logs",
                modifier = Modifier.fillMaxSize().padding(padding)
            )
        }
    }
}
