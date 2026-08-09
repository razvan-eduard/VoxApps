package com.voxapps.hub.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.voxapps.backup.VoxLocalBackupFile
import com.voxapps.backup.VoxSettingsRoundTrip
import com.voxapps.backup.ui.VoxBackupCardFeatures
import com.voxapps.backup.ui.VoxBackupSettingsCard
import com.voxapps.backup.ui.VoxBackupUiState
import com.voxapps.hub.data.preferences.HubSettings
import com.voxapps.hub.data.preferences.HubSettingsRepository
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val DOMAIN = "hub"

/**
 * Hub's own "back up to a file I pick right now" / "restore from a file I pick" section for its
 * own settings, built on the shared `:core:backup` card. Unlike the four satellite apps, Hub has
 * no "data"/API keys/attachments of its own to back up — that's what its existing scheduled/manual
 * Export flow already handles for every *other* app — so this card is settings-only, and hides
 * every other toggle plus the import-mode selector (there's no data record to reconcile, just one
 * settings blob to overwrite).
 */
@Composable
fun HubBackupSettingsSection(settingsRepo: HubSettingsRepository, settings: HubSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isBusy by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf<String?>(null) }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        isBusy = true
        scope.launch {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                VoxLocalBackupFile.write(out, context.contentResolver, DOMAIN, settings.toJson().toString(), emptyMap())
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
                resultMessage = "That file has no Hub backup in it"
                isBusy = false
                return@launch
            }
            settingsRepo.restoreSettings(payload.domainJson.toHubSettings())
            resultMessage = "Settings restored"
            isBusy = false
        }
    }

    VoxBackupSettingsCard(
        state = VoxBackupUiState(
            includeSettings = true,
            includeData = false,
            isBusy = isBusy,
            lastResultMessage = resultMessage
        ),
        features = VoxBackupCardFeatures(
            showSettingsToggle = false,
            showDataToggle = false,
            showApiKeysToggle = false,
            showAttachmentsToggle = false,
            showImportModeSelector = false
        ),
        onIncludeSettingsChange = {},
        onIncludeDataChange = {},
        onIncludeApiKeysChange = {},
        onIncludeAttachmentsChange = {},
        onImportModeChange = {},
        onBackupNowClick = {
            val fileName = "vox-hub-backup-${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())}.zip"
            createDocumentLauncher.launch(fileName)
        },
        onRestoreClick = { openDocumentLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }
    )
}

// Excludes the most-recent-scheduled-run status fields and voxConnectEnabled/voxConnectPort —
// all device-local runtime state per HubSettings' own doc comment, not portable user data.
private fun HubSettings.toJson(): JSONObject = JSONObject(
    VoxSettingsRoundTrip.toJson(
        copy(
            lastBackupSuccess = null,
            lastBackupTimestamp = null,
            lastBackupError = null,
            lastBackupMissingApps = emptyList(),
            voxConnectEnabled = false
        )
    )
)

private fun JSONObject.toHubSettings(): HubSettings =
    VoxSettingsRoundTrip.parseOrDefault(toString(), HubSettings::class.java, HubSettings())
