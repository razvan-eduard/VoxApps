package com.voxapps.hub.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.voxapps.design.DoubleBackToExitHandler
import com.voxapps.hub.domain.ExportImportUtil
import com.voxapps.ipc.VoxAppInfo
import com.voxapps.ipc.VoxAppsDiscovery
import com.voxapps.ipc.VoxDataTransferClient
import com.voxapps.ipc.VoxIpc
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class ImportPreview(
    val perDomain: Map<String, JSONObject>,
    val summaries: Map<String, Map<String, Int>>
)

/** Matches the "Granted" success color used in the shared onboarding permission rows. */
private val SuccessGreen = Color(0xFF2E7D32)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HubScreen(onOpenSettings: () -> Unit = {}) {
    val languageManager = LocalLanguageManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    DoubleBackToExitHandler(message = languageManager.getString("press_back_again_to_exit"))

    var apps by remember { mutableStateOf<List<VoxAppInfo>>(emptyList()) }
    var selectedForExport by remember { mutableStateOf<Set<String>>(emptySet()) }
    var exportScope by remember { mutableStateOf(VoxIpc.EXPORT_SCOPE_BOTH) }
    var isExporting by remember { mutableStateOf(false) }
    var exportStatus by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var exportOk by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }

    // Populated when an export attempt finds apps that never responded even to a ping — offers to
    // flash just those (not the whole selection) rather than blanket-prompting every time.
    var showFlashRetryDialog by remember { mutableStateOf(false) }
    var pendingExportUri by remember { mutableStateOf<Uri?>(null) }
    var pendingUnreachable by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingResults by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var pendingOkFlags by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var pendingPerDomainJson by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    var isImporting by remember { mutableStateOf(false) }
    var importPreview by remember { mutableStateOf<ImportPreview?>(null) }
    var selectedForImport by remember { mutableStateOf<Set<String>>(emptySet()) }
    var importStatus by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var importError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val discovered = VoxAppsDiscovery.discover(context).filter {
            it.actions.contains("export") || it.actions.contains("import")
        }
        apps = discovered
        selectedForExport = discovered.filter { it.actions.contains("export") }.map { it.packageName }.toSet()
    }

    fun finalizeExport(uri: Uri, perDomainJson: Map<String, String>) {
        if (perDomainJson.isNotEmpty()) {
            val document = ExportImportUtil.buildExportDocument(perDomainJson)
            context.contentResolver.openOutputStream(uri)?.use { it.write(document.toByteArray()) }
            Toast.makeText(context, languageManager.getString("hub_export_saved_toast"), Toast.LENGTH_SHORT).show()
        }
        isExporting = false
    }

    /**
     * Flashes only [targetPackages] (not the whole selection) — briefly starts each one's own
     * launcher activity to clear Android's "stopped" component state (the only way to do so; no
     * broadcast, including a ping, can), brings Hub back to front, then retries export for just
     * those apps and merges the result into the export already collected for everyone else.
     */
    fun retryUnreachableThenFinalize(
        uri: Uri,
        targetPackages: Set<String>,
        priorResults: Map<String, String>,
        priorOkFlags: Map<String, Boolean>,
        priorPerDomainJson: Map<String, String>
    ) {
        isExporting = true
        scope.launch {
            // Clearing Android's "stopped" flag requires the target app's activity to genuinely
            // reach RESUMED (visible) state, not merely be started — batching every launch into one
            // startActivities() call avoids BAL blocking but never lets the non-final entries
            // actually resume (confirmed via dumpsys: their stopped flag stayed true even though a
            // process spawned). So each target needs its own startActivity() call with enough delay
            // to really finish its transition — but a delay much past ~1s makes Hub's own next call
            // BAL-blocked (confirmed via logcat "Background activity launch blocked!" at 700ms).
            // 350ms threads that needle: long enough for a real activity transition, short enough to
            // stay inside Hub's post-tap BAL grace window for every call in the chain.
            for (pkg in targetPackages) {
                context.packageManager.getLaunchIntentForPackage(pkg)?.let { intent ->
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    delay(350L)
                }
            }
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { intent ->
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
            delay(300L)

            val results = priorResults.toMutableMap()
            val okFlags = priorOkFlags.toMutableMap()
            val perDomainJson = priorPerDomainJson.toMutableMap()
            for (app in apps.filter { it.packageName in targetPackages }) {
                val domain = app.domain ?: app.packageName
                val result = VoxDataTransferClient.requestExport(context, app.packageName, exportScope)
                if (result != null && result.ok) {
                    perDomainJson[domain] = result.text
                    results[app.packageName] = languageManager.getString("hub_status_ok")
                    okFlags[app.packageName] = true
                } else {
                    results[app.packageName] = result?.text?.takeIf { it.isNotBlank() }
                        ?: languageManager.getString("hub_status_timeout")
                    okFlags[app.packageName] = false
                }
                exportStatus = results.toMap()
                exportOk = okFlags.toMap()
            }
            finalizeExport(uri, perDomainJson)
        }
    }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val targets = apps.filter { it.packageName in selectedForExport && it.actions.contains("export") }
        isExporting = true
        exportStatus = emptyMap()
        exportOk = emptyMap()
        scope.launch {
            val results = mutableMapOf<String, String>()
            val okFlags = mutableMapOf<String, Boolean>()
            val perDomainJson = mutableMapOf<String, String>()
            val unreachable = mutableSetOf<String>()
            for (app in targets) {
                val domain = app.domain ?: app.packageName
                // Ping first, cheaply, with a cold-start-friendly timeout: a process that's simply
                // been backgrounded/memory-reclaimed (not explicitly stopped) will wake up and
                // answer here. If it DOESN'T — genuinely unreachable, e.g. never launched or Force
                // Stopped — skip the heavier export request entirely (it would just time out too)
                // and flag it for the flash-retry dialog instead of burning another ~10s on it.
                val reachable = VoxAppsDiscovery.ping(context, app.packageName, timeoutMs = 8_000L)
                if (!reachable) {
                    results[app.packageName] = languageManager.getString("hub_status_timeout")
                    okFlags[app.packageName] = false
                    unreachable += app.packageName
                } else {
                    val result = VoxDataTransferClient.requestExport(context, app.packageName, exportScope)
                    if (result != null && result.ok) {
                        perDomainJson[domain] = result.text
                        results[app.packageName] = languageManager.getString("hub_status_ok")
                        okFlags[app.packageName] = true
                    } else {
                        results[app.packageName] = result?.text?.takeIf { it.isNotBlank() }
                            ?: languageManager.getString("hub_status_timeout")
                        okFlags[app.packageName] = false
                    }
                }
                // Update per-app as each one finishes, not just at the very end, so the green
                // checkmark appears progressively while later apps are still exporting.
                exportStatus = results.toMap()
                exportOk = okFlags.toMap()
            }
            if (unreachable.isNotEmpty()) {
                pendingExportUri = uri
                pendingUnreachable = unreachable
                pendingResults = results
                pendingOkFlags = okFlags
                pendingPerDomainJson = perDomainJson
                showFlashRetryDialog = true
                isExporting = false
            } else {
                finalizeExport(uri, perDomainJson)
            }
        }
    }

    fun startExportFlow() {
        // Includes time-of-day, not just the date — otherwise multiple exports on the same day
        // collide on filename and the system file picker silently appends "(1)", "(2)", etc.,
        // which made past backups easy to mix up.
        val fileName = "vox-export-${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())}.json"
        createDocumentLauncher.launch(fileName)
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        importError = null
        scope.launch {
            try {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: throw IllegalStateException("empty file")
                val perDomain = ExportImportUtil.parseImportDocument(text)
                // Only offer domains that are both in the file AND currently installed/discovered.
                val available = perDomain.filterKeys { domain -> apps.any { it.domain == domain && it.actions.contains("import") } }
                if (available.isEmpty()) {
                    importError = languageManager.getString("hub_import_no_matching_apps")
                    return@launch
                }
                val summaries = available.mapValues { (_, data) -> ExportImportUtil.summarize(data) }
                importPreview = ImportPreview(available, summaries)
                selectedForImport = available.keys
                importStatus = emptyMap()
            } catch (e: Exception) {
                importError = languageManager.getString("hub_import_invalid_file")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(languageManager.getString("hub_title")) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = languageManager.getString("settings"))
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            if (apps.isEmpty()) {
                Text(languageManager.getString("hub_no_apps_found"))
            } else {
                Text(languageManager.getString("hub_export_section"), style = MaterialTheme.typography.titleMedium)
                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    items(apps.filter { it.actions.contains("export") }, key = { it.packageName }) { app ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Checkbox(
                                checked = app.packageName in selectedForExport,
                                onCheckedChange = { checked ->
                                    selectedForExport = if (checked) selectedForExport + app.packageName else selectedForExport - app.packageName
                                }
                            )
                            Column {
                                val ok = exportOk[app.packageName] == true
                                Text(
                                    app.label,
                                    color = if (ok) SuccessGreen else MaterialTheme.colorScheme.onSurface
                                )
                                exportStatus[app.packageName]?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (ok) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val options = listOf(
                        VoxIpc.EXPORT_SCOPE_SETTINGS to "hub_scope_settings",
                        VoxIpc.EXPORT_SCOPE_DATA to "hub_scope_data",
                        VoxIpc.EXPORT_SCOPE_BOTH to "hub_scope_both"
                    )
                    options.forEach { (value, labelKey) ->
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            RadioButton(selected = exportScope == value, onClick = { exportScope = value })
                            Text(languageManager.getString(labelKey), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Button(
                    onClick = { startExportFlow() },
                    enabled = !isExporting && selectedForExport.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isExporting) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    }
                    Text(languageManager.getString("hub_export_button"))
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                Text(languageManager.getString("hub_import_section"), style = MaterialTheme.typography.titleMedium)
                importError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
                }
                OutlinedButton(
                    onClick = { openDocumentLauncher.launch(arrayOf("application/json")) },
                    enabled = !isImporting,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Text(languageManager.getString("hub_import_button"))
                }
                importStatus.forEach { (domain, status) ->
                    Text("$domain: $status", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }

    importPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { importPreview = null },
            title = { Text(languageManager.getString("hub_import_confirm_title")) },
            text = {
                Column {
                    preview.summaries.forEach { (domain, counts) ->
                        val label = apps.firstOrNull { it.domain == domain }?.label ?: domain
                        val summaryText = counts.entries.joinToString(", ") { (key, count) -> "$count $key" }
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(
                                checked = domain in selectedForImport,
                                onCheckedChange = { checked ->
                                    selectedForImport = if (checked) selectedForImport + domain else selectedForImport - domain
                                }
                            )
                            Text("$label: $summaryText")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = selectedForImport.isNotEmpty(),
                    onClick = {
                        val targets = preview.perDomain.filterKeys { it in selectedForImport }
                        importPreview = null
                        isImporting = true
                        scope.launch {
                            val results = mutableMapOf<String, String>()
                            for ((domain, data) in targets) {
                                val app = apps.firstOrNull { it.domain == domain }
                                if (app == null) continue
                                // Same cold-start warm-up as export — see its comment above.
                                VoxAppsDiscovery.ping(context, app.packageName, timeoutMs = 8_000L)
                                val result = VoxDataTransferClient.requestImport(context, app.packageName, data.toString())
                                results[domain] = if (result != null && result.ok) {
                                    result.text
                                } else {
                                    result?.text?.takeIf { it.isNotBlank() } ?: languageManager.getString("hub_status_timeout")
                                }
                            }
                            importStatus = results
                            isImporting = false
                        }
                    }
                ) { Text(languageManager.getString("hub_import_apply_button")) }
            },
            dismissButton = {
                TextButton(onClick = { importPreview = null }) { Text(languageManager.getString("cancel")) }
            }
        )
    }

    if (showFlashRetryDialog) {
        val unreachableLabels = apps
            .filter { it.packageName in pendingUnreachable }
            .joinToString(", ") { it.label }
        AlertDialog(
            onDismissRequest = {
                showFlashRetryDialog = false
                pendingExportUri?.let { finalizeExport(it, pendingPerDomainJson) }
            },
            title = { Text(languageManager.getString("hub_prewarm_title")) },
            text = { Text(String.format(languageManager.getString("hub_prewarm_message"), unreachableLabels)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showFlashRetryDialog = false
                        val uri = pendingExportUri
                        if (uri != null) {
                            retryUnreachableThenFinalize(
                                uri = uri,
                                targetPackages = pendingUnreachable,
                                priorResults = pendingResults,
                                priorOkFlags = pendingOkFlags,
                                priorPerDomainJson = pendingPerDomainJson
                            )
                        }
                    }
                ) { Text(languageManager.getString("hub_prewarm_flash_button")) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showFlashRetryDialog = false
                        pendingExportUri?.let { finalizeExport(it, pendingPerDomainJson) }
                    }
                ) { Text(languageManager.getString("hub_prewarm_skip_button")) }
            }
        )
    }
}
