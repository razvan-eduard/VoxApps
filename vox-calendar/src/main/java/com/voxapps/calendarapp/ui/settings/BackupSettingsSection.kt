package com.voxapps.calendarapp.ui.settings

import android.net.Uri
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
import com.voxapps.backup.ui.VoxBackupCardFeatures
import com.voxapps.backup.ui.VoxBackupSettingsCard
import com.voxapps.backup.ui.VoxBackupUiState
import com.voxapps.calendarapp.CalendarApplication
import com.voxapps.calendarapp.data.preferences.CalendarSettings
import com.voxapps.calendarapp.data.preferences.CalendarSettingsRepository
import com.voxapps.calendarapp.receiver.CalendarExportImportHandler
import com.voxapps.ipc.VoxIpc
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val DOMAIN = "calendar"

/** Calendar's own "back up to a file I pick right now" / "restore from a file I pick" section,
 *  built on the shared `:core:backup` card. Calendar has no secrets concept, so
 *  `showApiKeysToggle = false`. */
@Composable
fun BackupSettingsSection(settingsRepo: CalendarSettingsRepository, settings: CalendarSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val container = remember { (context.applicationContext as CalendarApplication).container }
    val handler = remember {
        CalendarExportImportHandler(
            context, container.settingsRepository, container.sessionManager,
            container.calendarRepository, container.attachmentDao, container.toDoListDao
        )
    }

    var isBusy by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf<String?>(null) }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val exportScope = when {
            settings.backupIncludeSettings && settings.backupIncludeData -> VoxIpc.EXPORT_SCOPE_BOTH
            settings.backupIncludeSettings -> VoxIpc.EXPORT_SCOPE_SETTINGS
            settings.backupIncludeData -> VoxIpc.EXPORT_SCOPE_DATA
            else -> null
        }
        if (exportScope == null) {
            resultMessage = "Nothing selected to back up"
            return@rememberLauncherForActivityResult
        }
        isBusy = true
        scope.launch {
            val exportResult = handler.export(exportScope, includePhotos = settings.backupIncludeAttachments)
            val attachmentEntries = exportResult.attachmentUri?.let { mapOf("$DOMAIN-attachments.zip" to Uri.parse(it)) } ?: emptyMap()
            context.contentResolver.openOutputStream(uri)?.use { out ->
                VoxLocalBackupFile.write(out, context.contentResolver, DOMAIN, exportResult.text, attachmentEntries)
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
                resultMessage = "That file has no Calendar backup in it"
                isBusy = false
                return@launch
            }
            payload.attachmentUris["attachmentsZipUri"]?.let { payload.domainJson.put("attachmentsZipUri", it.toString()) }
            val result = handler.import(payload.domainJson.toString(), VoxImportMode.fromWireValue(settings.backupImportMode))
            resultMessage = result.text
            isBusy = false
        }
    }

    VoxBackupSettingsCard(
        state = VoxBackupUiState(
            includeSettings = settings.backupIncludeSettings,
            includeData = settings.backupIncludeData,
            includeApiKeys = false,
            includeAttachments = settings.backupIncludeAttachments,
            importMode = VoxImportMode.fromWireValue(settings.backupImportMode),
            isBusy = isBusy,
            lastResultMessage = resultMessage
        ),
        features = VoxBackupCardFeatures(showApiKeysToggle = false),
        onIncludeSettingsChange = { scope.launch { settingsRepo.setBackupIncludeSettings(it) } },
        onIncludeDataChange = { scope.launch { settingsRepo.setBackupIncludeData(it) } },
        onIncludeApiKeysChange = {},
        onIncludeAttachmentsChange = { scope.launch { settingsRepo.setBackupIncludeAttachments(it) } },
        onImportModeChange = { scope.launch { settingsRepo.setBackupImportMode(it.wireValue) } },
        onBackupNowClick = {
            val fileName = "vox-calendar-backup-${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())}.zip"
            createDocumentLauncher.launch(fileName)
        },
        onRestoreClick = { openDocumentLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }
    )
}
