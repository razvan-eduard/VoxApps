package com.voxcommander.app.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voxcommander.app.data.preferences.SettingsRepository
import com.voxcommander.app.domain.diagnostic.BenchmarkEngine
import com.voxcommander.app.domain.localization.LanguageManager
import com.voxcommander.app.state.AppStateManager
import com.voxcommander.app.state.BenchmarkResult
import com.voxcommander.app.state.VoiceState
import com.voxcommander.app.utils.Logger
import com.voxcommander.app.utils.LogLevel
import com.voxcommander.app.utils.LoggingFlags
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun AdvancedSettingsTab(
    languageManager: LanguageManager,
    settingsRepo: SettingsRepository,
    appStateManager: AppStateManager,
    onCleanupRequest: () -> Unit,
    onClearDefaultFallback: () -> Unit,
    onVerboseLoggingChange: (Boolean) -> Unit,
    refreshTrigger: Int = 0
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val uiState by appStateManager.uiState.collectAsStateWithLifecycle()
    val benchmarkResults by appStateManager.benchmarkResults.collectAsStateWithLifecycle()
    val nativeLibsStatus by appStateManager.nativeLibsStatus.collectAsStateWithLifecycle()
    val systemInfo by appStateManager.systemInfo.collectAsStateWithLifecycle()
    val logs by Logger.verboseLogs.collectAsStateWithLifecycle()

    val appContainer = remember { (context.applicationContext as com.voxcommander.app.VoxApplication).container }
    val benchmarkEngine = remember {
        BenchmarkEngine(
            context,
            appContainer.settingsRepository,
            appStateManager,
            appContainer.modelDownloader,
            appContainer.localLlmInterpreter,
            appContainer.geminiNanoInterpreter,
            appContainer.geminiCloudInterpreter
        )
    }
    val isRunning = uiState.voiceState == VoiceState.BENCHMARKING
    var showRestartDialog by remember { mutableStateOf(false) }
    var showDisableDialog by remember { mutableStateOf(false) }
    var showMeteredWarning by remember { mutableStateOf(false) }
    var showWifiOnlyBlocked by remember { mutableStateOf(false) }
    var pendingDownloadSize by remember { mutableStateOf("") }
    var isDownloadingWhisper by remember { mutableStateOf(false) }
    var whisperDownloadProgress by remember { mutableStateOf(0f) }

    // Manage own state with logging flags
    var loggingFlags by remember {
        mutableStateOf(
            LoggingFlags.fromLogLevel(
                when (settingsRepo.getSettingsSnapshot().logLevel) {
                    "NONE" -> LogLevel.NONE
                    "TOAST_ONLY" -> LogLevel.TOAST_ONLY
                    "LOGCAT_ONLY" -> LogLevel.LOGCAT_ONLY
                    else -> LogLevel.TOAST_AND_LOGCAT
                }
            )
        )
    }
    var verboseLoggingEnabled by remember(loggingFlags.logcatEnabled) {
        mutableStateOf(
            if (loggingFlags.logcatEnabled) {
                settingsRepo.getSettingsSnapshot().verboseLoggingEnabled
            } else {
                false
            }
        )
    }

    LaunchedEffect(loggingFlags.logcatEnabled) {
        if (!loggingFlags.logcatEnabled) {
            verboseLoggingEnabled = false
            kotlinx.coroutines.runBlocking { settingsRepo.setVerboseLoggingEnabled(false) }
            Logger.setVerboseLoggingEnabled(false)
            onVerboseLoggingChange(false)
        } else {
            val savedVerbose = settingsRepo.getSettingsSnapshot().verboseLoggingEnabled
            verboseLoggingEnabled = savedVerbose
            Logger.setVerboseLoggingEnabled(savedVerbose)
            onVerboseLoggingChange(savedVerbose)
        }
    }

    LaunchedEffect(verboseLoggingEnabled) {
        onVerboseLoggingChange(verboseLoggingEnabled)
    }

    LaunchedEffect(Unit) {
        appStateManager.refreshNativeLibsStatus()
        Logger.initialize(context, LoggingFlags.toLogLevel(loggingFlags))
        Logger.setLoggingFlags(loggingFlags)
        Logger.setVerboseLoggingEnabled(verboseLoggingEnabled)
    }

    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- LOGGING SECTION ---
        item {
            Text(text = languageManager.getString("advanced_settings_section"), style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = languageManager.getString("logging_level"), style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val enabled = !loggingFlags.toastEnabled
                        loggingFlags = loggingFlags.copy(toastEnabled = enabled)
                        val newLogLevel = LoggingFlags.toLogLevel(loggingFlags)
                        kotlinx.coroutines.runBlocking { settingsRepo.setLogLevel(newLogLevel.name) }
                        Logger.setLoggingFlags(loggingFlags)
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(languageManager.getString("toast_label"))
                Checkbox(
                    checked = loggingFlags.toastEnabled,
                    onCheckedChange = { enabled ->
                        loggingFlags = loggingFlags.copy(toastEnabled = enabled)
                        val newLogLevel = LoggingFlags.toLogLevel(loggingFlags)
                        kotlinx.coroutines.runBlocking { settingsRepo.setLogLevel(newLogLevel.name) }
                        Logger.setLoggingFlags(loggingFlags)
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val enabled = !loggingFlags.logcatEnabled
                        loggingFlags = loggingFlags.copy(logcatEnabled = enabled)
                        val newLogLevel = LoggingFlags.toLogLevel(loggingFlags)
                        kotlinx.coroutines.runBlocking { settingsRepo.setLogLevel(newLogLevel.name) }
                        Logger.setLoggingFlags(loggingFlags)
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(languageManager.getString("logcat_label"))
                Checkbox(
                    checked = loggingFlags.logcatEnabled,
                    onCheckedChange = { enabled ->
                        loggingFlags = loggingFlags.copy(logcatEnabled = enabled)
                        val newLogLevel = LoggingFlags.toLogLevel(loggingFlags)
                        kotlinx.coroutines.runBlocking { settingsRepo.setLogLevel(newLogLevel.name) }
                        Logger.setLoggingFlags(loggingFlags)
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    languageManager.getString("verbose_logging"),
                    color = if (loggingFlags.logcatEnabled) LocalContentColor.current else Color.Gray
                )
                Switch(
                    checked = verboseLoggingEnabled,
                    onCheckedChange = { enabled ->
                        verboseLoggingEnabled = enabled
                        kotlinx.coroutines.runBlocking { settingsRepo.setVerboseLoggingEnabled(enabled) }
                        Logger.setVerboseLoggingEnabled(enabled)
                    },
                    enabled = loggingFlags.logcatEnabled
                )
            }
        }

        // --- VERBOSE LOGS SECTION ---
        if (verboseLoggingEnabled) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(text = languageManager.getString("verbose_logging_section"), style = MaterialTheme.typography.titleMedium)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Button(
                                onClick = { Logger.clearVerboseLogs() },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(languageManager.getString("clear_logs"), style = MaterialTheme.typography.labelSmall)
                            }
                            Button(
                                onClick = {
                                    val logText = logs.joinToString("\n") { logEntry ->
                                        val timestamp = dateFormat.format(Date(logEntry.timestamp))
                                        "[$timestamp] [${logEntry.tag}] ${logEntry.message}"
                                    }
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    val clip = android.content.ClipData.newPlainText("VoxCommander Logs", logText)
                                    clipboard.setPrimaryClip(clip)
                                },
                                modifier = Modifier.weight(1f),
                                enabled = logs.isNotEmpty(),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = languageManager.getString("copy_button"), modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(languageManager.getString("copy_button"), style = MaterialTheme.typography.labelSmall)
                            }
                            Button(
                                onClick = {
                                    val logText = logs.joinToString("\n") { logEntry ->
                                        val timestamp = dateFormat.format(Date(logEntry.timestamp))
                                        "[$timestamp] [${logEntry.tag}] ${logEntry.message}"
                                    }
                                    val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(android.content.Intent.EXTRA_TEXT, logText)
                                        putExtra(android.content.Intent.EXTRA_SUBJECT, "VoxCommander Logs")
                                    }
                                    context.startActivity(android.content.Intent.createChooser(shareIntent, "Share Logs"))
                                },
                                modifier = Modifier.weight(1f),
                                enabled = logs.isNotEmpty(),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = languageManager.getString("share_button"), modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(languageManager.getString("share_button"), style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        if (logs.isEmpty()) {
                            Text(
                                text = languageManager.getString("no_logs"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                logs.forEach { logEntry ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                    ) {
                                        Column(modifier = Modifier.padding(8.dp)) {
                                            Text(
                                                text = dateFormat.format(Date(logEntry.timestamp)),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.secondary
                                            )
                                            Text(
                                                text = "[${logEntry.tag}]",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Text(
                                                text = logEntry.message,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- BENCHMARK SECTION ---
        item {
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = languageManager.getString("global_engine_benchmark"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(text = languageManager.getString("benchmark_description"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { scope.launch { benchmarkEngine.runFullBenchmark() } },
                        enabled = !isRunning,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isRunning) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(languageManager.getString("running_all_tests"))
                        } else {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(languageManager.getString("start_benchmark"))
                        }
                    }
                }
            }
        }

        if (benchmarkResults.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = languageManager.getString("performance_metrics"), style = MaterialTheme.typography.titleSmall)
                    IconButton(onClick = {
                        val report = buildBenchmarkReport(benchmarkResults, systemInfo)
                        val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_SUBJECT, "Vox Commander Benchmark Report")
                            putExtra(android.content.Intent.EXTRA_TEXT, report)
                        }
                        context.startActivity(android.content.Intent.createChooser(shareIntent, "Share Benchmark Report"))
                    }) {
                        Icon(Icons.Default.Share, contentDescription = "Share", modifier = Modifier.size(18.dp))
                    }
                }
            }
            items(benchmarkResults) { result -> BenchmarkResultItem(result, languageManager) }
        }

        // --- DOWNLOAD PREFERENCE ---
        item {
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = languageManager.getString("download_preference"), style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(languageManager.getString("download_preference_wifi_only"), style = MaterialTheme.typography.bodyMedium)
                        RadioButton(
                            selected = uiState.downloadPreference == "wifi_only",
                            onClick = { appStateManager.setDownloadPreference("wifi_only") }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(languageManager.getString("download_preference_wifi_and_metered"), style = MaterialTheme.typography.bodyMedium)
                        RadioButton(
                            selected = uiState.downloadPreference == "wifi_and_metered",
                            onClick = { appStateManager.setDownloadPreference("wifi_and_metered") }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // --- EXPERIMENTAL FEATURES ---
        item {
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "Experimental Features", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // --- Whisper Engine (DLC) ---

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Whisper STT Engine", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                            Text(
                                if (uiState.isWhisperSystemEnabled) "On-device Whisper engine is enabled. Disable to remove and free space."
                                else "Download the Whisper engine (~147MB) for offline speech recognition.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = uiState.isWhisperSystemEnabled,
                            enabled = !isDownloadingWhisper,
                            onCheckedChange = { enabled ->
                                if (enabled) {
                                    val whisperSize = languageManager.getString("whisper_libs_size")
                                    if (uiState.downloadPreference == "wifi_only" && com.voxcommander.app.utils.NetworkMonitor.isMetered) {
                                        pendingDownloadSize = whisperSize
                                        showWifiOnlyBlocked = true
                                    } else if (com.voxcommander.app.utils.NetworkMonitor.isMetered) {
                                        pendingDownloadSize = whisperSize
                                        showMeteredWarning = true
                                    } else {
                                        scope.launch {
                                            isDownloadingWhisper = true
                                            whisperDownloadProgress = 0f
                                            val success = appContainer.whisperEngineManager.enable { progress ->
                                                whisperDownloadProgress = progress
                                            }
                                            isDownloadingWhisper = false
                                            if (success) {
                                                showRestartDialog = true
                                            }
                                        }
                                    }
                                } else {
                                    showDisableDialog = true
                                }
                            }
                        )
                    }

                    if (isDownloadingWhisper) {
                        LinearProgressIndicator(
                            progress = { whisperDownloadProgress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "Downloading Whisper engine... ${(whisperDownloadProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    HorizontalDivider()

                    // --- Whisper Vulkan (Experimental) ---
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Whisper Vulkan (Experimental)", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                            Text("Enable GPU acceleration via Vulkan. May cause crashes on some devices.", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = uiState.isExperimentalVulkanEnabled,
                            enabled = uiState.isWhisperSystemEnabled,
                            onCheckedChange = { appStateManager.setExperimentalVulkanEnabled(it) }
                        )
                    }
                }
            }
        }

        // --- SYSTEM MAINTENANCE ---
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = languageManager.getString("system_maintenance"), style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.1f))) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onCleanupRequest, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                        Text(languageManager.getString("delete_unused_models"))
                    }
                    Button(onClick = onClearDefaultFallback, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)) {
                        Text(languageManager.getString("clear_default_fallback"))
                    }
                    Text(text = languageManager.getString("maintenance_warning"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // --- TUTORIAL SECTION ---
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = languageManager.getString("tutorial_section"), style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            appStateManager.setTutorialCompleted(false)
                            appStateManager.setFirstLaunchCompleted(false)
                            restartApp(context)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(languageManager.getString("replay_tutorial"))
                    }
                    Text(
                        text = languageManager.getString("tutorial_description"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    // --- RESTART DIALOG ---
    if (showRestartDialog) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Whisper Engine Installed") },
            text = { Text("Whisper STT engine has been downloaded successfully. The app needs to restart to load the new libraries.") },
            confirmButton = {
                TextButton(onClick = {
                    showRestartDialog = false
                    restartApp(context)
                }) {
                    Text("Restart Now")
                }
            }
        )
    }

    // --- DISABLE DIALOG ---
    if (showDisableDialog) {
        AlertDialog(
            onDismissRequest = { showDisableDialog = false },
            title = { Text("Disable Whisper Engine") },
            text = { Text("All local Whisper features will be disabled. Downloaded Whisper models and native libraries (.so files) will be deleted to free space. The app will restart.") },
            confirmButton = {
                TextButton(onClick = {
                    showDisableDialog = false
                    scope.launch {
                        appContainer.whisperEngineManager.disable(deleteLibs = true, deleteModels = true)
                        restartApp(context)
                    }
                }) {
                    Text("Disable & Restart")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisableDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // --- METERED WARNING DIALOG ---
    if (showMeteredWarning) {
        AlertDialog(
            onDismissRequest = { showMeteredWarning = false },
            title = { Text(languageManager.getString("metered_warning_title")) },
            text = { Text(languageManager.getString("metered_warning_msg").format(pendingDownloadSize)) },
            confirmButton = {
                TextButton(onClick = {
                    showMeteredWarning = false
                    scope.launch {
                        isDownloadingWhisper = true
                        whisperDownloadProgress = 0f
                        val success = appContainer.whisperEngineManager.enable { progress ->
                            whisperDownloadProgress = progress
                        }
                        isDownloadingWhisper = false
                        if (success) {
                            showRestartDialog = true
                        }
                    }
                }) {
                    Text(languageManager.getString("metered_warning_continue"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showMeteredWarning = false }) {
                    Text(languageManager.getString("cancel_button"))
                }
            }
        )
    }

    // --- WIFI ONLY BLOCKED DIALOG ---
    if (showWifiOnlyBlocked) {
        AlertDialog(
            onDismissRequest = { showWifiOnlyBlocked = false },
            title = { Text(languageManager.getString("wifi_only_blocked_title")) },
            text = { Text(languageManager.getString("wifi_only_blocked_msg").format(pendingDownloadSize)) },
            confirmButton = {
                TextButton(onClick = { showWifiOnlyBlocked = false }) {
                    Text(languageManager.getString("ok_button"))
                }
            }
        )
    }
}

private fun restartApp(context: android.content.Context) {
    com.jakewharton.processphoenix.ProcessPhoenix.triggerRebirth(context)
}

private fun buildBenchmarkReport(results: List<BenchmarkResult>, systemInfo: String): String {
    val sb = StringBuilder()
    sb.append("=== Vox Commander Benchmark Report ===\n")
    sb.append("Date: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n\n")
    sb.append("--- PERFORMANCE METRICS ---\n")
    for (r in results) {
        val status = if (r.isSuccess) "OK" else "FAIL"
        val detail = if (r.isSuccess) {
            if (r.rtf > 0f) "${r.inferenceTimeMs}ms, RTF=${String.format(Locale.US, "%.2f", r.rtf)}" else "${r.inferenceTimeMs}ms"
        } else {
            r.error ?: "unknown"
        }
        sb.append("[$status] ${r.engine} (${r.model}): $detail\n")
    }
    if (systemInfo.isNotBlank()) {
        sb.append("\n--- SYSTEM DIAGNOSTICS ---\n")
        sb.append(systemInfo)
    }
    sb.append("\n=== End of Report ===\n")
    return sb.toString()
}
