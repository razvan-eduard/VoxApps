package com.voxapps.commander.ui.screens.settings

import com.voxapps.commander.ui.LocalLanguageManager

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
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.domain.diagnostic.BenchmarkEngine
import com.voxapps.commander.domain.localization.LanguageManager
import com.voxapps.commander.state.AppStateManager
import com.voxapps.commander.state.BenchmarkResult
import com.voxapps.commander.state.VoiceState
import com.voxapps.logging.Logger
import com.voxapps.logging.ui.LogViewerCard
import com.voxapps.logging.ui.LogViewerStrings
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun AdvancedSettingsTab(

    settingsRepo: SettingsRepository,
    appStateManager: AppStateManager,
    onCleanupRequest: () -> Unit,
    onClearDefaultFallback: () -> Unit,
    refreshTrigger: Int = 0
) {
        val languageManager = LocalLanguageManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val uiState by appStateManager.uiState.collectAsStateWithLifecycle()
    var showResetSettings by remember { mutableStateOf(false) }
    val benchmarkResults by appStateManager.benchmarkResults.collectAsStateWithLifecycle()
    val systemInfo by appStateManager.systemInfo.collectAsStateWithLifecycle()
    val logs by Logger.verboseLogs.collectAsStateWithLifecycle()
    val gpuTestDeferred by appStateManager.gpuTestDeferred.collectAsStateWithLifecycle()
    val gpuNoBackend by appStateManager.gpuNoBackend.collectAsStateWithLifecycle()
    val settings by settingsRepo.settingsFlow.collectAsStateWithLifecycle(initialValue = settingsRepo.getSettingsSnapshot())

    val appContainer = remember { (context.applicationContext as com.voxapps.commander.VoxApplication).container }
    val benchmarkEngine = remember {
        BenchmarkEngine(
            context,
            appContainer.settingsRepository,
            appStateManager,
            appContainer.modelDownloader,
            appContainer.fastMapDao,
            appContainer.localLlmInterpreter
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

    LaunchedEffect(Unit) {
        appStateManager.refreshNativeLibsStatus()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- BENCHMARK SECTION ---
        item {
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
            items(benchmarkResults) { result -> BenchmarkResultItem(result) }
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
            Text(text = languageManager.getString("engine_model_management"), style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // --- Cloud AI engines ---
                    // Restricts engines the schema declares `runtime: "cloud"` — no engine names
                    // are hardcoded here or anywhere the gate is applied.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(languageManager.getString("cloud_intelligence_title"), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                            Text(languageManager.getString("cloud_intelligence_desc"), style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = uiState.cloudIntelligenceEnabled,
                            onCheckedChange = { appStateManager.setCloudIntelligenceEnabled(it) }
                        )
                    }

                    // --- Google system services ---
                    // Restricts engines the schema marks with the "google_service" capability.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(languageManager.getString("google_services_title"), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                            Text(languageManager.getString("google_services_desc"), style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = uiState.googleServicesEnabled,
                            onCheckedChange = { appStateManager.setGoogleServicesEnabled(it) }
                        )
                    }

                    HorizontalDivider()

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
                                    if (uiState.downloadPreference == "wifi_only" && com.voxapps.commander.utils.NetworkMonitor.isMetered) {
                                        pendingDownloadSize = whisperSize
                                        showWifiOnlyBlocked = true
                                    } else if (com.voxapps.commander.utils.NetworkMonitor.isMetered) {
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

                    // --- GPU acceleration (Experimental), one switch per engine ---
                    // The switch IS the consent gesture: enabling arms the one-shot isolated
                    // probe, an incompatible verdict greys it out, and "Test again" forgets
                    // this device's verdict so the next enable re-proves it.
                    GpuAccelerationRow(
                        title = languageManager.getString("gpu_whisper_title"),
                        engine = com.voxapps.commander.data.preferences.SettingsRepository.GPU_WHISPER,
                        enabled = uiState.whisperGpuEnabled,
                        switchable = uiState.isWhisperSystemEnabled,
                        incompatible = uiState.whisperGpuIncompatible,
                        verified = uiState.whisperGpuRuntimeVerified,
                        probeDone = uiState.whisperGpuProbeDone,
                        languageManager = languageManager,
                        appStateManager = appStateManager
                    )

                    HorizontalDivider()

                    GpuAccelerationRow(
                        title = languageManager.getString("gpu_llama_title"),
                        engine = com.voxapps.commander.data.preferences.SettingsRepository.GPU_LLAMA,
                        enabled = uiState.llamaGpuEnabled,
                        switchable = true,
                        incompatible = uiState.llamaGpuIncompatible,
                        verified = uiState.llamaGpuRuntimeVerified,
                        probeDone = uiState.llamaGpuProbeDone,
                        languageManager = languageManager,
                        appStateManager = appStateManager
                    )

                    HorizontalDivider()

                    Button(onClick = onCleanupRequest, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                        Text(languageManager.getString("delete_unused_models"))
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
                    Button(onClick = onClearDefaultFallback, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)) {
                        Text(languageManager.getString("clear_default_fallback"))
                    }
                    Text(text = languageManager.getString("maintenance_warning"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    // Resetting the settings lived in a second card under its own heading, and that
                    // heading read "System maintenance" — the same words as this one, one line
                    // apart, so the screen appeared to have the section twice. All three are
                    // destructive maintenance actions, so they belong in the one card.
                    //
                    // No "reset schemas" here: turning off *Use schemas from the repository* in
                    // General is that reset, and one way to undo something is enough.
                    OutlinedButton(
                        onClick = { showResetSettings = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(languageManager.getString("reset_settings_button"))
                    }
                    Text(
                        text = languageManager.getString("reset_settings_description"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

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

        // --- LOGGING SECTION (same two-switch + viewer shape as every other app's Logs tab) ---
        // Deliberately the page's last section: the viewer below it grows without bound while
        // logging is on, and anything placed after it would sit below an ever-longer scroll.
        item {
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(languageManager.getString("debug_logging"), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        languageManager.getString("debug_logging_desc"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.debugLoggingEnabled,
                    onCheckedChange = { appStateManager.setDebugLoggingEnabled(it) }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Dependent on debug logging being on — same as every other app's Logs tab, and
            // matches the fact that a toast is just an alternate rendering of the same log event.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    languageManager.getString("debug_toasts_label"),
                    color = if (settings.debugLoggingEnabled) LocalContentColor.current else Color.Gray
                )
                Switch(
                    checked = settings.debugToastsEnabled,
                    enabled = settings.debugLoggingEnabled,
                    onCheckedChange = { appStateManager.setDebugToastsEnabled(it) }
                )
            }
        }

        // --- VERBOSE LOGS SECTION (shared viewer, same as every other app's Logs tab) ---
        if (settings.debugLoggingEnabled) {
            item {
                LogViewerCard(
                    logs = logs,
                    strings = LogViewerStrings(
                        sectionTitle = languageManager.getString("verbose_logging_section"),
                        clearLabel = languageManager.getString("clear_logs"),
                        copyLabel = languageManager.getString("copy_button"),
                        shareLabel = languageManager.getString("share_button"),
                        noLogsLabel = languageManager.getString("no_logs")
                    ),
                    shareSubject = "VoxCommander Logs"
                )
            }
        }

    }

    // --- GPU NO-BACKEND DIALOG ---
    // The probe ran and found nothing to test: no GPU backend in the build, or no usable device.
    // Said out loud because the switch snapping back off with no verdict line would otherwise
    // look like a silent malfunction.
    if (gpuNoBackend != null) {
        AlertDialog(
            onDismissRequest = { appStateManager.dismissGpuNoBackend() },
            title = { Text(languageManager.getString("gpu_no_backend_title")) },
            text = { Text(languageManager.getString("gpu_no_backend_message")) },
            confirmButton = {
                TextButton(onClick = { appStateManager.dismissGpuNoBackend() }) {
                    Text(languageManager.getString("ok"))
                }
            }
        )
    }

    // --- GPU TEST DEFERRED DIALOG ---
    // Enabling the switch normally answers the compatibility question on the spot. With no model
    // loaded for that engine there is nothing to answer it with, so say when the answer will come
    // rather than leaving a switch on with no verdict behind it.
    if (gpuTestDeferred != null) {
        AlertDialog(
            onDismissRequest = { appStateManager.dismissGpuTestDeferred() },
            title = { Text(languageManager.getString("gpu_test_deferred_title")) },
            text = { Text(languageManager.getString("gpu_test_deferred_message")) },
            confirmButton = {
                TextButton(onClick = { appStateManager.dismissGpuTestDeferred() }) {
                    Text(languageManager.getString("ok"))
                }
            }
        )
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
    if (showResetSettings) {
        AlertDialog(
            onDismissRequest = { showResetSettings = false },
            title = { Text(languageManager.getString("reset_settings_button")) },
            text = { Text(languageManager.getString("reset_settings_confirm")) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        settingsRepo.clearAllSettings()
                        showResetSettings = false
                        restartApp(context)
                    }
                }) { Text(languageManager.getString("ok_button"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showResetSettings = false }) {
                    Text(languageManager.getString("cancel_button"))
                }
            }
        )
    }
}

private fun restartApp(context: android.content.Context) {
    com.jakewharton.processphoenix.ProcessPhoenix.triggerRebirth(context)
}

/** One engine's GPU switch with its verdict line and, once a verdict exists, "Test again". */
@Composable
private fun GpuAccelerationRow(
    title: String,
    engine: String,
    enabled: Boolean,
    switchable: Boolean,
    incompatible: Boolean,
    verified: Boolean,
    probeDone: Boolean,
    languageManager: LanguageManager,
    appStateManager: AppStateManager
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                Text(languageManager.getString("gpu_toggle_desc"), style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = enabled,
                enabled = switchable && !incompatible,
                onCheckedChange = { appStateManager.setGpuEnabled(engine, it) }
            )
        }
        val verdict = when {
            incompatible -> languageManager.getString("gpu_verdict_incompatible")
            verified -> languageManager.getString("gpu_verdict_verified")
            probeDone -> languageManager.getString("gpu_verdict_probed")
            else -> null
        }
        if (verdict != null) {
            // Stacked rather than sharing a row: a verdict runs to a full sentence, and beside it
            // the button was squeezed to a column of single letters.
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    verdict,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (incompatible) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = { appStateManager.clearGpuVerdict(engine) },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(languageManager.getString("gpu_test_again"))
                }
            }
        }
    }
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
