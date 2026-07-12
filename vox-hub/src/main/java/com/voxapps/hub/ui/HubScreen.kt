package com.voxapps.hub.ui

import android.net.Uri
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.voxapps.hub.domain.ExportImportUtil
import com.voxapps.ipc.VoxAppInfo
import com.voxapps.ipc.VoxAppsDiscovery
import com.voxapps.ipc.VoxDataTransferClient
import com.voxapps.ipc.VoxIpc
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class ImportPreview(
    val perDomain: Map<String, JSONObject>,
    val summaries: Map<String, Map<String, Int>>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HubScreen() {
    val languageManager = LocalLanguageManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var apps by remember { mutableStateOf<List<VoxAppInfo>>(emptyList()) }
    var selectedForExport by remember { mutableStateOf<Set<String>>(emptySet()) }
    var exportScope by remember { mutableStateOf(VoxIpc.EXPORT_SCOPE_BOTH) }
    var isExporting by remember { mutableStateOf(false) }
    var exportStatus by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

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

    val createDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val targets = apps.filter { it.packageName in selectedForExport && it.actions.contains("export") }
        isExporting = true
        exportStatus = emptyMap()
        scope.launch {
            val results = mutableMapOf<String, String>()
            val perDomainJson = mutableMapOf<String, String>()
            for (app in targets) {
                val domain = app.domain ?: app.packageName
                val result = VoxDataTransferClient.requestExport(context, app.packageName, exportScope)
                if (result != null && result.ok) {
                    perDomainJson[domain] = result.text
                    results[app.packageName] = languageManager.getString("hub_status_ok")
                } else {
                    results[app.packageName] = result?.text?.takeIf { it.isNotBlank() }
                        ?: languageManager.getString("hub_status_timeout")
                }
            }
            exportStatus = results
            if (perDomainJson.isNotEmpty()) {
                val document = ExportImportUtil.buildExportDocument(perDomainJson)
                context.contentResolver.openOutputStream(uri)?.use { it.write(document.toByteArray()) }
            }
            isExporting = false
        }
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
        topBar = { TopAppBar(title = { Text(languageManager.getString("hub_title")) }) }
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
                                Text(app.label)
                                exportStatus[app.packageName]?.let {
                                    Text(it, style = MaterialTheme.typography.labelSmall)
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
                    onClick = {
                        val fileName = "vox-export-${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.json"
                        createDocumentLauncher.launch(fileName)
                    },
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
}
