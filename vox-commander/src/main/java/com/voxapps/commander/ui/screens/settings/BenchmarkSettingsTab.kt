package com.voxapps.commander.ui.screens.settings

import com.voxapps.commander.data.remote.RemoteModelRegistry
import com.voxapps.commander.ui.LocalLanguageManager
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voxapps.commander.domain.diagnostic.BenchmarkEngine
import com.voxapps.commander.domain.localization.LanguageManager
import com.voxapps.commander.state.AppStateManager
import com.voxapps.commander.state.BenchmarkResult
import com.voxapps.commander.state.VoiceState
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun BenchmarkSettingsTab(

    appStateManager: AppStateManager,
    refreshTrigger: Int = 0
) {
        val languageManager = LocalLanguageManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uiState by appStateManager.uiState.collectAsStateWithLifecycle()
    val benchmarkResults by appStateManager.benchmarkResults.collectAsStateWithLifecycle()
    val nativeLibsStatus by appStateManager.nativeLibsStatus.collectAsStateWithLifecycle()
    val systemInfo by appStateManager.systemInfo.collectAsStateWithLifecycle()
    
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

    LaunchedEffect(Unit) {
        appStateManager.refreshNativeLibsStatus()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
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
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Vox Commander Benchmark Report")
                            putExtra(Intent.EXTRA_TEXT, report)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share Benchmark Report"))
                    }) {
                        Icon(Icons.Default.Share, contentDescription = "Share", modifier = Modifier.size(18.dp))
                    }
                }
            }
            items(benchmarkResults) { result -> BenchmarkResultItem(result) }
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(text = languageManager.getString("native_library_inventory"), style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = { appStateManager.refreshNativeLibsStatus() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(18.dp))
                }
            }
        }
        
        item {
            // Grouped by the engine each library belongs to, and titled from the registry — the
            // same name the engine has everywhere else, in the user's language. This was three
            // hardcoded filters against three invented category names, with three hardcoded English
            // titles beside them, so an engine's libraries could only be grouped by editing here.
            // Anything belonging to no single engine keeps its own group at the end.
            val byEngine = nativeLibsStatus.filter { it.category.isNotBlank() }.groupBy { it.category }
            val otherLibs = nativeLibsStatus.filter { it.category.isBlank() }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                byEngine.forEach { (engineKey, libs) ->
                    EngineLibGroupCard(RemoteModelRegistry.getEngineLabel(engineKey, languageManager), libs)
                }
                if (otherLibs.isNotEmpty()) EngineLibGroupCard("System & Runtime", otherLibs)
            }
        }

        if (systemInfo.isNotBlank()) {
            item {
                Text(text = languageManager.getString("engine_runtime_diagnostics"), style = MaterialTheme.typography.titleSmall)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.Black)
                ) {
                    val annotatedInfo = buildAnnotatedString {
                        systemInfo.split("\n").forEach { line ->
                            val isError = line.contains("INCOMPATIBLE") || line.contains("Error") || line.contains("Failed")
                            withStyle(style = SpanStyle(color = if (isError) Color.Red else Color.Green)) {
                                append(line + "\n")
                            }
                        }
                    }
                    Text(
                        text = annotatedInfo,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(12.dp),
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

@Composable
fun BenchmarkResultItem(result: BenchmarkResult) {
        val languageManager = LocalLanguageManager.current
    Card(modifier = Modifier.fillMaxWidth(), border = BorderStroke(1.dp, if (result.isSuccess) Color.Gray.copy(alpha = 0.3f) else Color.Red)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "${result.engine} (${result.model})", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                if (result.isSuccess) {
                    if (result.rtf > 0f) {
                        Text(
                            text = languageManager.getString("time_rtf_format").format(result.inferenceTimeMs, String.format(Locale.US, "%.2f", result.rtf)),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (result.rtf < 0.5f) Color(0xFF4CAF50) else Color.Gray
                        )
                    } else {
                        Text(
                            text = "${result.inferenceTimeMs} ms",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF4CAF50)
                        )
                    }
                } else {
                    Text(text = languageManager.getString("error_format").format(result.error), style = MaterialTheme.typography.bodySmall, color = Color.Red)
                }
            }
            if (result.isSuccess) Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CAF50))
            else Icon(Icons.Default.Error, contentDescription = null, tint = Color.Red)
        }
    }
}

private fun buildBenchmarkReport(results: List<BenchmarkResult>, systemInfo: String): String {
    val sb = StringBuilder()
    sb.append("=== Vox Commander Benchmark Report ===\n")
    sb.append("Date: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(java.util.Date())}\n\n")

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

@Composable
fun EngineLibGroupCard(title: String, libs: List<com.voxapps.commander.state.NativeLibStatus>) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            libs.forEach { lib -> NativeLibStatusItem(lib) }
        }
    }
}

@Composable
fun NativeLibStatusItem(lib: com.voxapps.commander.state.NativeLibStatus) {
    val statusColor = when {
        lib.isIncompatible -> Color.Red
        lib.isActive && lib.exists -> Color.Green
        !lib.isActive && lib.exists -> Color(0xFF2196F3) // Blue (Standby)
        lib.isActive && !lib.exists -> Color.Red // CRITICAL: Active but missing
        else -> Color.Gray // Not present, not active
    }

    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(10.dp).background(statusColor, shape = androidx.compose.foundation.shape.CircleShape))
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(text = lib.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                text = when {
                    lib.isIncompatible -> "${lib.description}"
                    lib.isActive && lib.exists -> "${lib.description} (Active)"
                    !lib.isActive && lib.exists -> "${lib.description} (Ready)"
                    lib.isActive && !lib.exists -> "${lib.description} (MISSING!)"
                    else -> "${lib.description} (Not Installed)"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (lib.isIncompatible || (lib.isActive && !lib.exists)) Color.Red else Color.Gray
            )
        }
    }
}
