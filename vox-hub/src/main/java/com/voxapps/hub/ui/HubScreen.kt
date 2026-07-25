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
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxapps.design.DoubleBackToExitHandler
import com.voxapps.hub.data.preferences.HubSettings
import com.voxapps.hub.data.preferences.HubSettingsRepository
import com.voxapps.hub.domain.ExportImportUtil
import com.voxapps.hub.domain.backup.AppBackupConfig
import com.voxapps.hub.domain.backup.BackupZipWriter
import com.voxapps.hub.domain.backup.configFor
import com.voxapps.hub.domain.backup.requestExportFor
import com.voxapps.hub.domain.backup.wantsExport
import com.voxapps.hub.domain.backup.zipEntriesFor
import com.voxapps.ipc.VoxAppInfo
import com.voxapps.ipc.VoxAppsDiscovery
import com.voxapps.ipc.VoxDataTransferClient
import com.voxapps.ipc.VoxIpc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream

private data class ImportPreview(
    val perDomain: Map<String, JSONObject>,
    val summaries: Map<String, Map<String, Int>>
)

/** One attachment zip staged from an import file, ready to inject into its owning domain's import
 *  JSON under [fieldName] (see [com.voxapps.hub.ui.HubScreen]'s readExportDocument). */
private data class StagedZipAttachment(val domain: String, val fieldName: String, val uri: Uri)

/** Matches the "Granted" success color used in the shared onboarding permission rows. */
private val SuccessGreen = Color(0xFF2E7D32)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HubScreen(
    settingsRepo: HubSettingsRepository,
    restoreFileUri: Uri? = null,
    onOpenSettings: () -> Unit = {},
    onOpenSync: () -> Unit = {}
) {
    val languageManager = LocalLanguageManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    DoubleBackToExitHandler(message = languageManager.getString("press_back_again_to_exit"))

    // Scheduled backups run with no UI visible, so a failure has to surface here, the next time
    // the user opens Hub — this is the only way they'd otherwise find out. Dismissing just
    // remembers the timestamp already shown (a local "seen" flag, not the underlying record), so a
    // *later* failure re-shows even if an earlier one was dismissed.
    val settings by settingsRepo.settingsFlow.collectAsStateWithLifecycle(initialValue = HubSettings())
    var dismissedFailureTimestamp by remember { mutableStateOf<Long?>(null) }

    var apps by remember { mutableStateOf<List<VoxAppInfo>>(emptyList()) }
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
    // Zip-entry-name -> content URI (see zipEntriesFor) for every domain whose export() call
    // returned an attachment/secondaryAttachment URI, bundled into the final export zip.
    var pendingAttachmentZipEntries by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    var isImporting by remember { mutableStateOf(false) }
    var importPreview by remember { mutableStateOf<ImportPreview?>(null) }
    var selectedForImport by remember { mutableStateOf<Set<String>>(emptySet()) }
    var importStatus by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var importError by remember { mutableStateOf<String?>(null) }

    // Same flash-retry mechanism as export (see showFlashRetryDialog below) — a domain's app that's
    // never been opened or was Force Stopped won't even answer a ping, so import for it needs the
    // same "open it once to clear Android's stopped state, then retry" offer.
    var showImportFlashRetryDialog by remember { mutableStateOf(false) }
    var pendingImportUnreachable by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingImportTargets by remember { mutableStateOf<Map<String, JSONObject>>(emptyMap()) }
    var pendingImportResults by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    LaunchedEffect(Unit) {
        apps = VoxAppsDiscovery.discover(context).filter {
            it.actions.contains("export") || it.actions.contains("import")
        }
    }

    fun finalizeExport(uri: Uri, perDomainJson: Map<String, String>, attachmentZipEntries: Map<String, String>) {
        if (perDomainJson.isNotEmpty()) {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                BackupZipWriter.write(out, context.contentResolver, perDomainJson, attachmentZipEntries)
            }
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
        priorPerDomainJson: Map<String, String>,
        priorAttachmentZipEntries: Map<String, String>
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
            val attachmentZipEntries = priorAttachmentZipEntries.toMutableMap()
            for (app in apps.filter { it.packageName in targetPackages }) {
                val domain = app.domain ?: app.packageName
                val result = requestExportFor(context, app, settings.appBackupConfigs.configFor(app.packageName))
                if (result != null && result.ok) {
                    perDomainJson[domain] = result.text
                    attachmentZipEntries += zipEntriesFor(domain, result)
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
            finalizeExport(uri, perDomainJson, attachmentZipEntries)
        }
    }

    fun finalizeImport(results: Map<String, String>) {
        importStatus = results
        isImporting = false
    }

    /**
     * Flashes only [targetPackages] (not the whole selection), same as [retryUnreachableThenFinalize]
     * above, then retries import for just the domains backed by those packages and merges the result
     * into what already succeeded for everyone else.
     */
    fun retryImportUnreachableThenFinalize(
        targetPackages: Set<String>,
        targets: Map<String, JSONObject>,
        priorResults: Map<String, String>
    ) {
        isImporting = true
        scope.launch {
            // Same BAL-grace-window-tuned flash sequence as export — see its comment above.
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
            for ((domain, data) in targets) {
                val app = apps.firstOrNull { it.domain == domain } ?: continue
                if (app.packageName !in targetPackages) continue
                val result = VoxDataTransferClient.requestImport(context, app.packageName, data.toString())
                results[domain] = if (result != null && result.ok) {
                    result.text
                } else {
                    result?.text?.takeIf { it.isNotBlank() } ?: languageManager.getString("hub_status_timeout")
                }
                importStatus = results.toMap()
            }
            finalizeImport(results)
        }
    }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val targets = apps.filter { app ->
            app.actions.contains("export") &&
                settings.appBackupConfigs.configFor(app.packageName).wantsExport()
        }
        isExporting = true
        exportStatus = emptyMap()
        exportOk = emptyMap()
        scope.launch {
            val results = mutableMapOf<String, String>()
            val okFlags = mutableMapOf<String, Boolean>()
            val perDomainJson = mutableMapOf<String, String>()
            val attachmentZipEntries = mutableMapOf<String, String>()
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
                    val result = requestExportFor(context, app, settings.appBackupConfigs.configFor(app.packageName))
                    if (result != null && result.ok) {
                        perDomainJson[domain] = result.text
                        attachmentZipEntries += zipEntriesFor(domain, result)
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
                pendingAttachmentZipEntries = attachmentZipEntries
                showFlashRetryDialog = true
                isExporting = false
            } else {
                finalizeExport(uri, perDomainJson, attachmentZipEntries)
            }
        }
    }

    fun startExportFlow() {
        // Includes time-of-day, not just the date — otherwise multiple exports on the same day
        // collide on filename and the system file picker silently appends "(1)", "(2)", etc.,
        // which made past backups easy to mix up.
        val fileName = "vox-export-${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())}.zip"
        createDocumentLauncher.launch(fileName)
    }

    /**
     * Tries reading [uri] as a zip containing "export.json" (+ optionally per-domain attachment
     * zips); falls back to treating the whole file as raw JSON text if it's not a valid zip, or is a
     * zip with no "export.json" entry — this is what makes old (pre-zip) plain-JSON export files
     * still importable. Every staged attachment zip's bytes are copied into Hub's own cache and the
     * owning domain's app is granted read access immediately, so the returned URIs are usable as
     * soon as that domain's import request is sent later in the flow. Recognizes both the legacy
     * exact entry name `"expenses-receipts.zip"` (kept for backward compatibility with already-created
     * backup files, injected as the `receiptsZipUri` field Expenses' import() already reads) and the
     * newer `"$domain-attachments.zip"` pattern for any domain (injected as `attachmentsZipUri`,
     * see :core:attachments).
     */
    suspend fun readExportDocument(uri: Uri): Pair<String, List<StagedZipAttachment>> = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("empty file")
        var documentText: String? = null
        val staged = mutableListOf<StagedZipAttachment>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    val fieldAndDomain = when {
                        name == "export.json" -> null
                        name == "expenses-receipts.zip" -> "receiptsZipUri" to "expenses"
                        name.endsWith("-attachments.zip") -> "attachmentsZipUri" to name.removeSuffix("-attachments.zip")
                        else -> null
                    }
                    if (name == "export.json") {
                        documentText = zis.readBytes().decodeToString()
                    } else if (fieldAndDomain != null) {
                        val (fieldName, domain) = fieldAndDomain
                        val stagedDir = File(context.cacheDir, "imports").apply { mkdirs() }
                        val stagedFile = File(stagedDir, "import_${domain}_${UUID.randomUUID()}.zip")
                        FileOutputStream(stagedFile).use { fos -> zis.copyTo(fos) }
                        val stagedUri = FileProvider.getUriForFile(context, "com.voxapps.hub.fileprovider", stagedFile)
                        apps.firstOrNull { it.domain == domain }?.packageName?.let { pkg ->
                            context.grantUriPermission(pkg, stagedUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        staged += StagedZipAttachment(domain, fieldName, stagedUri)
                    }
                    entry = zis.nextEntry
                }
            }
        } catch (e: Exception) {
            documentText = null
        }
        (documentText ?: bytes.decodeToString()) to staged
    }

    // Shared by the manual "Choose file to import" picker below and the Settings screen's
    // per-backup "Restore" action (which hands in a FileProvider Uri for one of its own
    // on-disk scheduled-backup files instead of a user-picked one) — same preview/confirm flow
    // either way.
    fun startImportFrom(uri: Uri) {
        importError = null
        scope.launch {
            try {
                val (text, stagedAttachments) = readExportDocument(uri)
                val perDomain = ExportImportUtil.parseImportDocument(text)
                // Single mutation, visible from every later consumer of this same JSONObject
                // instance (importPreview.perDomain, the confirm loop's targets, and the flash-retry
                // path's pendingImportTargets) — avoids injecting this at multiple requestImport
                // call sites where it could drift out of sync.
                for (attachment in stagedAttachments) {
                    perDomain[attachment.domain]?.put(attachment.fieldName, attachment.uri.toString())
                }
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

    val openDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) startImportFrom(uri)
    }

    // Set by HubActivity right before switching from Settings back to this screen, when the user
    // tapped "Restore" on one of the Past backups entries — this is the trigger, not a picker
    // result, so it's consumed via LaunchedEffect instead of an ActivityResult callback. Only
    // fires again if a *different* backup's Uri is set next (LaunchedEffect keys on the value).
    LaunchedEffect(restoreFileUri) {
        restoreFileUri?.let { startImportFrom(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(languageManager.getString("hub_title")) },
                actions = {
                    IconButton(onClick = onOpenSync) {
                        Icon(Icons.Filled.Nfc, contentDescription = languageManager.getString("sync_title"))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = languageManager.getString("settings"))
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            val backupTimestamp = settings.lastBackupTimestamp
            if (settings.lastBackupSuccess == false && backupTimestamp != null && backupTimestamp != dismissedFailureTimestamp) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            languageManager.getString("backup_failed_banner_title"),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            String.format(
                                languageManager.getString("backup_failed_banner_message"),
                                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(backupTimestamp)),
                                settings.lastBackupError ?: ""
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        TextButton(
                            onClick = { dismissedFailureTimestamp = backupTimestamp },
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Text(languageManager.getString("dismiss_button"))
                        }
                    }
                }
            }
            if (apps.isEmpty()) {
                Text(languageManager.getString("hub_no_apps_found"))
            } else {
                Text(languageManager.getString("hub_export_section"), style = MaterialTheme.typography.titleMedium)
                Text(
                    languageManager.getString("hub_backup_config_shared_note"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                val exportableApps = apps.filter { it.actions.contains("export") }
                val anySecretsOn = exportableApps.any { settings.appBackupConfigs.configFor(it.packageName).includeApiKeys }
                val allFullyOn = exportableApps.isNotEmpty() && exportableApps.all { app ->
                    val c = settings.appBackupConfigs.configFor(app.packageName)
                    c.includeSettings && c.includeData && c.includeApiKeys && c.includeAttachments
                }
                if (exportableApps.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Text(languageManager.getString("hub_toggle_all"), style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = allFullyOn,
                            onCheckedChange = { turnOn ->
                                val next = AppBackupConfig(
                                    includeSettings = turnOn,
                                    includeData = turnOn,
                                    includeApiKeys = turnOn,
                                    includeAttachments = turnOn
                                )
                                scope.launch {
                                    exportableApps.forEach { app -> settingsRepo.setAppBackupConfig(app.packageName, next) }
                                }
                            }
                        )
                    }
                }
                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    items(exportableApps, key = { it.packageName }) { app ->
                        val config = settings.appBackupConfigs.configFor(app.packageName)
                        val hasData = app.actions.contains("create")
                        fun update(next: AppBackupConfig) {
                            scope.launch { settingsRepo.setAppBackupConfig(app.packageName, next) }
                        }
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                val ok = exportOk[app.packageName] == true
                                Text(
                                    app.label,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (ok) SuccessGreen else MaterialTheme.colorScheme.onSurface
                                )
                                exportStatus[app.packageName]?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (ok) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                HubBackupToggleRow(
                                    label = languageManager.getString("hub_toggle_settings"),
                                    checked = config.includeSettings,
                                    onCheckedChange = { update(config.copy(includeSettings = it)) }
                                )
                                if (hasData) {
                                    HubBackupToggleRow(
                                        label = languageManager.getString("hub_toggle_data"),
                                        checked = config.includeData,
                                        onCheckedChange = { update(config.copy(includeData = it)) }
                                    )
                                }
                                HubBackupToggleRow(
                                    label = languageManager.getString("hub_toggle_api_keys"),
                                    checked = config.includeApiKeys,
                                    enabled = config.includeSettings,
                                    onCheckedChange = { update(config.copy(includeApiKeys = it)) }
                                )
                                if (hasData) {
                                    HubBackupToggleRow(
                                        label = languageManager.getString("hub_toggle_attachments"),
                                        checked = config.includeAttachments,
                                        enabled = config.includeData,
                                        onCheckedChange = { update(config.copy(includeAttachments = it)) }
                                    )
                                }
                            }
                        }
                    }
                }
                if (anySecretsOn) {
                    Text(
                        languageManager.getString("hub_include_secrets_warning"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                Button(
                    onClick = { startExportFlow() },
                    enabled = !isExporting && apps.any { app ->
                        app.actions.contains("export") &&
                            settings.appBackupConfigs.configFor(app.packageName).wantsExport()
                    },
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
                    onClick = { openDocumentLauncher.launch(arrayOf("application/zip", "application/json", "*/*")) },
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
                            val unreachable = mutableSetOf<String>()
                            for ((domain, data) in targets) {
                                val app = apps.firstOrNull { it.domain == domain }
                                if (app == null) continue
                                // Same "ping first, flag genuinely unreachable apps instead of just
                                // timing out on the real request" pattern as export — see its comment
                                // above.
                                val reachable = VoxAppsDiscovery.ping(context, app.packageName, timeoutMs = 8_000L)
                                if (!reachable) {
                                    results[domain] = languageManager.getString("hub_status_timeout")
                                    unreachable += app.packageName
                                } else {
                                    val result = VoxDataTransferClient.requestImport(context, app.packageName, data.toString())
                                    results[domain] = if (result != null && result.ok) {
                                        result.text
                                    } else {
                                        result?.text?.takeIf { it.isNotBlank() } ?: languageManager.getString("hub_status_timeout")
                                    }
                                }
                                importStatus = results.toMap()
                            }
                            if (unreachable.isNotEmpty()) {
                                pendingImportUnreachable = unreachable
                                pendingImportTargets = targets
                                pendingImportResults = results
                                showImportFlashRetryDialog = true
                                isImporting = false
                            } else {
                                finalizeImport(results)
                            }
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
                pendingExportUri?.let { finalizeExport(it, pendingPerDomainJson, pendingAttachmentZipEntries) }
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
                                priorPerDomainJson = pendingPerDomainJson,
                                priorAttachmentZipEntries = pendingAttachmentZipEntries
                            )
                        }
                    }
                ) { Text(languageManager.getString("hub_prewarm_flash_button")) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showFlashRetryDialog = false
                        pendingExportUri?.let { finalizeExport(it, pendingPerDomainJson, pendingAttachmentZipEntries) }
                    }
                ) { Text(languageManager.getString("hub_prewarm_skip_button")) }
            }
        )
    }

    if (showImportFlashRetryDialog) {
        val unreachableLabels = apps
            .filter { it.packageName in pendingImportUnreachable }
            .joinToString(", ") { it.label }
        AlertDialog(
            onDismissRequest = {
                showImportFlashRetryDialog = false
                finalizeImport(pendingImportResults)
            },
            title = { Text(languageManager.getString("hub_prewarm_title")) },
            text = { Text(String.format(languageManager.getString("hub_prewarm_message"), unreachableLabels)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showImportFlashRetryDialog = false
                        retryImportUnreachableThenFinalize(
                            targetPackages = pendingImportUnreachable,
                            targets = pendingImportTargets,
                            priorResults = pendingImportResults
                        )
                    }
                ) { Text(languageManager.getString("hub_prewarm_flash_button")) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showImportFlashRetryDialog = false
                        finalizeImport(pendingImportResults)
                    }
                ) { Text(languageManager.getString("hub_prewarm_skip_button")) }
            }
        )
    }
}

/** One row of a per-app backup-config section — [enabled] disables (not hides) a toggle whose
 *  parent is off (API keys needs Settings on, Attachments needs Data on), same pattern
 *  HubSettingsScreen already uses for debug_toasts under debugLoggingEnabled. */
@Composable
private fun HubBackupToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Switch(checked = checked && enabled, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
