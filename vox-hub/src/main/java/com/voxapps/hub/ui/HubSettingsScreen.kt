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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
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
import com.voxapps.hub.data.preferences.HubSettings
import com.voxapps.hub.data.preferences.HubSettingsRepository
import com.voxapps.hub.domain.backup.BackupScheduler
import com.voxapps.logging.Logger
import com.voxapps.logging.ui.LogViewerCard
import com.voxapps.logging.ui.LogViewerStrings
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/** Row height for a Past-backups entry (two-line text + icon buttons) — used to cap the list at
 *  5 visible rows regardless of how many backups exist. */
private const val BACKUP_ROW_HEIGHT = 64

/** Hub's settings screen: theme controls, scheduled-backup configuration + past-backups list, and
 *  debug logging (mirrors the satellites' GeneralSettingsTab/LogsSettingsTab shape). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HubSettingsScreen(
    settingsRepo: HubSettingsRepository,
    onBack: () -> Unit,
    onRestoreBackup: (File) -> Unit
) {
    val languageManager = LocalLanguageManager.current
    val context = LocalContext.current
    val settings by settingsRepo.settingsFlow.collectAsStateWithLifecycle(initialValue = HubSettings())
    val logs by Logger.verboseLogs.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var backupFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    fun refreshBackupFiles() {
        val dir = File(context.getExternalFilesDir(null), "backups")
        backupFiles = (dir.listFiles { f -> f.isFile && f.name.endsWith(".zip") }
            ?.sortedByDescending { it.name } ?: emptyList())
    }
    LaunchedEffect(Unit) { refreshBackupFiles() }

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
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
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

            HorizontalDivider()

            // --- Scheduled backups: frequency, retention, past backups ---
            Text(languageManager.getString("backup_schedule_section"), style = MaterialTheme.typography.labelLarge)
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

            HorizontalDivider()

            // --- Debug logging ---
            Text(languageManager.getString("logs_settings_title"), style = MaterialTheme.typography.labelLarge)
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
    }
}
