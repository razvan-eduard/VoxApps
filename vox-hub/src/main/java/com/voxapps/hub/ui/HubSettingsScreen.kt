package com.voxapps.hub.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxapps.design.VoxDarkMode
import com.voxapps.design.settings.SettingsSectionHeader
import com.voxapps.design.settings.ThemeSettingsScreen
import com.voxapps.design.settings.ThemeSettingsStrings
import com.voxapps.hub.data.preferences.HubSettings
import com.voxapps.hub.data.preferences.HubSettingsRepository
import com.voxapps.hub.domain.backup.BackupScheduler
import com.voxapps.hub.domain.backup.configFor
import com.voxapps.hub.domain.backup.wantsExport
import com.voxapps.ipc.VoxAppInfo
import com.voxapps.ipc.VoxAppsDiscovery
import com.voxapps.logging.Logger
import com.voxapps.logging.ui.LogViewerCard
import com.voxapps.logging.ui.LogViewerStrings
import com.voxapps.voxconnect.PairedDeviceStore
import com.voxapps.voxconnect.VoxConnectPairing
import com.voxapps.voxconnect.VoxConnectServer
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/** Row height for a Past-backups entry (two-line text + icon buttons) — used to cap the list at
 *  5 visible rows regardless of how many backups exist. */
private const val BACKUP_ROW_HEIGHT = 64

private enum class SettingsPage { MENU, GENERAL, THEME, LOGS, VOXCONNECT }

/** Hub's settings screen: a menu/subpage split (mirrors the satellites' SettingsScreen shape) with
 *  scheduled-backup configuration + past-backups list under General, theme controls under Theme via
 *  the shared [ThemeSettingsScreen], and debug logging under Logs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HubSettingsScreen(
    settingsRepo: HubSettingsRepository,
    voxConnectServer: VoxConnectServer,
    voxConnectPairing: VoxConnectPairing,
    voxConnectDeviceStore: PairedDeviceStore,
    onBack: () -> Unit,
    onRestoreBackup: (File) -> Unit
) {
    val languageManager = LocalLanguageManager.current
    val context = LocalContext.current
    val settings by settingsRepo.settingsFlow.collectAsStateWithLifecycle(initialValue = HubSettings())
    val logs by Logger.verboseLogs.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Same discovery HubScreen's Export section uses — needed here only to tell whether *any*
    // app currently wants anything exported, so scheduling controls can be disabled when nothing
    // is selected (mirrors the Export button's own enabled condition on the main screen).
    var exportApps by remember { mutableStateOf<List<VoxAppInfo>>(emptyList()) }
    LaunchedEffect(Unit) {
        exportApps = VoxAppsDiscovery.discover(context).filter { it.actions.contains("export") }
    }
    val anyAppSelected = exportApps.any { settings.appBackupConfigs.configFor(it.packageName).wantsExport() }

    var backupFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    fun refreshBackupFiles() {
        val dir = File(context.getExternalFilesDir(null), "backups")
        backupFiles = (dir.listFiles { f -> f.isFile && f.name.endsWith(".zip") }
            ?.sortedByDescending { it.name } ?: emptyList())
    }
    LaunchedEffect(Unit) { refreshBackupFiles() }

    var page by remember { mutableStateOf(SettingsPage.MENU) }

    // Without this, the system back gesture/button falls through to the Activity's default
    // behavior (no back stack, single Activity) and closes the app instead of returning to the
    // main screen — matches the same fix already applied in vox-notes'/vox-expenses' SettingsScreen.
    BackHandler { if (page == SettingsPage.MENU) onBack() else page = SettingsPage.MENU }

    val title = when (page) {
        SettingsPage.MENU -> languageManager.getString("settings")
        SettingsPage.GENERAL -> languageManager.getString("backup_schedule_section")
        SettingsPage.THEME -> languageManager.getString("theme_section")
        SettingsPage.LOGS -> languageManager.getString("logs_settings_title")
        SettingsPage.VOXCONNECT -> languageManager.getString("voxconnect_section")
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
            SettingsPage.MENU -> Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                SettingsSectionHeader(languageManager.getString("settings_section_general"))
                ListItem(
                    headlineContent = { Text(languageManager.getString("backup_schedule_section")) },
                    leadingContent = { Icon(Icons.Filled.Backup, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().clickable { page = SettingsPage.GENERAL }
                )
                SettingsSectionHeader(languageManager.getString("settings_section_appearance"))
                ListItem(
                    headlineContent = { Text(languageManager.getString("theme_section")) },
                    leadingContent = { Icon(Icons.Filled.Palette, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().clickable { page = SettingsPage.THEME }
                )
                SettingsSectionHeader(languageManager.getString("settings_section_integrations"))
                ListItem(
                    headlineContent = { Text(languageManager.getString("voxconnect_section")) },
                    leadingContent = { Icon(Icons.Filled.Wifi, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().clickable { page = SettingsPage.VOXCONNECT }
                )
                SettingsSectionHeader(languageManager.getString("settings_section_advanced"))
                ListItem(
                    headlineContent = { Text(languageManager.getString("logs_settings_title")) },
                    leadingContent = { Icon(Icons.Filled.BugReport, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().clickable { page = SettingsPage.LOGS }
                )
            }

            SettingsPage.GENERAL -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // --- Scheduled backups: frequency, retention, past backups ---
                Text(
                    languageManager.getString("backup_schedule_uses_main_screen_config"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!anyAppSelected) {
                    Text(
                        languageManager.getString("backup_schedule_nothing_selected"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val frequencies = listOf(
                        HubSettings.INTERVAL_OFF to "backup_frequency_off",
                        HubSettings.INTERVAL_DAILY to "backup_frequency_daily",
                        HubSettings.INTERVAL_WEEKLY to "backup_frequency_weekly",
                        HubSettings.INTERVAL_MONTHLY to "backup_frequency_monthly"
                    )
                    frequencies.forEach { (interval, labelKey) ->
                        FilterChip(
                            selected = settings.backupInterval == interval,
                            enabled = anyAppSelected,
                            onClick = {
                                scope.launch { settingsRepo.setBackupInterval(interval) }
                                BackupScheduler.reschedule(context, interval)
                            },
                            label = { Text(languageManager.getString(labelKey)) }
                        )
                    }
                }

                Text(languageManager.getString("backup_retention_label"), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val retentions = listOf(
                        HubSettings.RETENTION_NONE to "backup_retention_none",
                        HubSettings.RETENTION_2 to "backup_retention_2",
                        HubSettings.RETENTION_5 to "backup_retention_5",
                        HubSettings.RETENTION_10 to "backup_retention_10",
                        HubSettings.RETENTION_UNLIMITED to "backup_retention_unlimited"
                    )
                    retentions.forEach { (count, labelKey) ->
                        FilterChip(
                            selected = settings.backupRetentionCount == count,
                            enabled = anyAppSelected,
                            onClick = { scope.launch { settingsRepo.setBackupRetentionCount(count) } },
                            label = { Text(languageManager.getString(labelKey)) }
                        )
                    }
                }
                if (settings.backupRetentionCount == HubSettings.RETENTION_UNLIMITED) {
                    Text(
                        languageManager.getString("backup_retention_unlimited_warning"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (backupFiles.isNotEmpty()) {
                    Text(languageManager.getString("backup_list_title"), style = MaterialTheme.typography.bodyMedium)
                    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
                    // Fixed to 5 rows' worth of height regardless of how many backups exist (unbounded
                    // under RETENTION_UNLIMITED) — this list scrolls on its own instead of pushing the
                    // rest of the settings screen down.
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = BACKUP_ROW_HEIGHT.dp * minOf(backupFiles.size, 5))
                    ) {
                        items(backupFiles, key = { it.name }) { file ->
                            Row(
                                modifier = Modifier.fillMaxWidth().height(BACKUP_ROW_HEIGHT.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(dateFormat.format(file.lastModified()), style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "${file.length() / 1024} KB",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = { onRestoreBackup(file) }) {
                                    Icon(Icons.Filled.Restore, contentDescription = languageManager.getString("backup_restore_action"))
                                }
                                IconButton(onClick = {
                                    val uri = FileProvider.getUriForFile(context, "com.voxapps.hub.fileprovider", file)
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "application/zip"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, languageManager.getString("backup_share_action")))
                                }) {
                                    Icon(Icons.Filled.Share, contentDescription = languageManager.getString("backup_share_action"))
                                }
                            }
                        }
                    }
                }
            }

            SettingsPage.THEME -> ThemeSettingsScreen(
                darkMode = runCatching { VoxDarkMode.valueOf(settings.themeDarkMode) }.getOrDefault(VoxDarkMode.SYSTEM),
                colored = settings.themeColored,
                onDarkModeChange = { scope.launch { settingsRepo.setThemeDarkMode(it.name) } },
                onColoredChange = { scope.launch { settingsRepo.setThemeColored(it) } },
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

            SettingsPage.LOGS -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(languageManager.getString("debug_logging"), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            languageManager.getString("debug_logging_desc"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.debugLoggingEnabled,
                        onCheckedChange = {
                            Logger.setEnabled(it)
                            scope.launch { settingsRepo.setDebugLoggingEnabled(it) }
                        }
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(languageManager.getString("debug_toasts_label"), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = settings.debugToastsEnabled,
                        enabled = settings.debugLoggingEnabled,
                        onCheckedChange = {
                            Logger.setToastsEnabled(it)
                            scope.launch { settingsRepo.setDebugToastsEnabled(it) }
                        }
                    )
                }
                if (settings.debugLoggingEnabled) {
                    LogViewerCard(
                        logs = logs,
                        strings = LogViewerStrings(
                            sectionTitle = languageManager.getString("verbose_logging_section"),
                            clearLabel = languageManager.getString("clear_logs"),
                            copyLabel = languageManager.getString("copy_button"),
                            shareLabel = languageManager.getString("share_button"),
                            noLogsLabel = languageManager.getString("no_logs")
                        ),
                        shareSubject = "VoxHub Logs"
                    )
                }
            }

            SettingsPage.VOXCONNECT -> VoxConnectSettingsCard(
                settings = settings,
                settingsRepo = settingsRepo,
                voxConnectServer = voxConnectServer,
                voxConnectPairing = voxConnectPairing,
                voxConnectDeviceStore = voxConnectDeviceStore,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            )
        }
    }
}
