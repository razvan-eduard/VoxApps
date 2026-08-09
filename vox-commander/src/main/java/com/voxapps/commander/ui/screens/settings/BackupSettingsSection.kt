package com.voxapps.commander.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.voxapps.backup.VoxImportMode
import com.voxapps.backup.VoxLocalBackupFile
import com.voxapps.backup.VoxSnapshotReplaceImporter
import com.voxapps.backup.ui.VoxBackupCardFeatures
import com.voxapps.backup.ui.VoxBackupSettingsCard
import com.voxapps.backup.ui.VoxBackupUiState
import com.voxapps.commander.VoxApplication
import com.voxapps.commander.data.preferences.AppSettings
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.receiver.CommanderExportHandler
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val DOMAIN = "commander"

/**
 * Commander's own "back up to a file I pick right now" / "restore from a file I pick" section,
 * built on the shared `:core:backup` card. Commander has no attachments concept, so
 * `showAttachmentsToggle = false`; "Data" here means FastMapRules, the only non-settings payload
 * Commander exports.
 */
@Composable
fun BackupSettingsSection(
    settingsRepo: SettingsRepository,
    settings: AppSettings,
    // Handed in rather than read from the repository: credentials reach the UI through
    // AppStateManager like every other piece of state, and a backup must carry what is on screen.
    credentials: com.voxapps.commander.data.preferences.Credentials
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val appContainer = remember { (context.applicationContext as VoxApplication).container }

    var isBusy by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf<String?>(null) }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        isBusy = true
        scope.launch {
            val json = JSONObject()
            if (settings.backupIncludeSettings) {
                val keys = if (settings.backupIncludeApiKeys) settingsRepo.getAllSearchProviderApiKeys() else emptyMap()
                json.put(
                    "settings",
                    JSONObject(
                        CommanderExportHandler.buildExportJson(
                            settings,
                            settings.backupIncludeApiKeys,
                            keys,
                            credentials
                        )
                    )
                )
            }
            if (settings.backupIncludeData) {
                val rules = appContainer.fastMapDao.getAllRulesOnce()
                json.put("fastMapRules", org.json.JSONArray(CommanderExportHandler.buildFastMapRulesJson(rules)))
            }
            context.contentResolver.openOutputStream(uri)?.use { out ->
                VoxLocalBackupFile.write(out, context.contentResolver, DOMAIN, json.toString(), emptyMap())
            }
            resultMessage = "Backup saved"
            isBusy = false
        }
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        isBusy = true
        scope.launch {
            val payload = VoxLocalBackupFile.readForDomain(context, uri, DOMAIN)
            if (payload == null) {
                resultMessage = "That file has no Commander backup in it"
                isBusy = false
                return@launch
            }
            val root = payload.domainJson
            val importMode = VoxImportMode.fromWireValue(settings.backupImportMode)

            val importedSettings = root.optJSONObject("settings")?.toString()
                ?.let { CommanderExportHandler.parsePortableSettings(it) }
            if (importedSettings != null) {
                settingsRepo.restoreImportedSettings(importedSettings)
            }

            var rulesImported = 0
            root.optJSONArray("fastMapRules")?.toString()?.let { rulesJson ->
                CommanderExportHandler.parseFastMapRules(rulesJson)?.let { rules ->
                    val preExisting = appContainer.fastMapDao.getAllRulesOnce()
                    rulesImported = VoxSnapshotReplaceImporter.restore(
                        mode = importMode,
                        imported = rules,
                        preExisting = preExisting,
                        insert = { appContainer.fastMapDao.insertRule(it.copy(id = 0)); 1L },
                        delete = { appContainer.fastMapDao.deleteRule(it) }
                    )
                }
            }

            resultMessage = "Restored settings" + (if (rulesImported > 0) " and $rulesImported FastMap rules" else "")
            isBusy = false
        }
    }

    VoxBackupSettingsCard(
        state = VoxBackupUiState(
            includeSettings = settings.backupIncludeSettings,
            includeData = settings.backupIncludeData,
            includeApiKeys = settings.backupIncludeApiKeys,
            includeAttachments = false,
            importMode = VoxImportMode.fromWireValue(settings.backupImportMode),
            isBusy = isBusy,
            lastResultMessage = resultMessage
        ),
        features = VoxBackupCardFeatures(showAttachmentsToggle = false),
        onIncludeSettingsChange = { scope.launch { settingsRepo.setBackupIncludeSettings(it) } },
        onIncludeDataChange = { scope.launch { settingsRepo.setBackupIncludeData(it) } },
        onIncludeApiKeysChange = { scope.launch { settingsRepo.setBackupIncludeApiKeys(it) } },
        onIncludeAttachmentsChange = {},
        onImportModeChange = { scope.launch { settingsRepo.setBackupImportMode(it.wireValue) } },
        onBackupNowClick = {
            val fileName = "vox-commander-backup-${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())}.zip"
            createDocumentLauncher.launch(fileName)
        },
        onRestoreClick = { openDocumentLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }
    )
}
