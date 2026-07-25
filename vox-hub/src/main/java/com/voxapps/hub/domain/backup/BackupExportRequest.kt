package com.voxapps.hub.domain.backup

import android.content.Context
import com.voxapps.ipc.VoxAppInfo
import com.voxapps.ipc.VoxDataTransferClient
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxResult

/** Whether [config] wants anything exported for this app at all — both Settings and Data off means
 *  "skip entirely," subsuming the old per-app include/exclude checklist. */
fun AppBackupConfig.wantsExport(): Boolean = includeSettings || includeData

/** Shared by the manual Export button ([com.voxapps.hub.ui.HubScreen]) and [BackupWorker] — one
 *  request/response call for a single app given its persisted [AppBackupConfig], so both paths map
 *  the four toggles to the underlying scope/secrets/photos IPC contract identically. */
/** Maps a domain's [VoxResult] attachment URIs to the zip-entry names [BackupZipWriter] should write
 *  — kept in one place since both the manual Export flow and [BackupWorker] build this map.
 *  "expenses" keeps its exact legacy entry name for the pre-existing receipts zip (populated via
 *  [VoxResult.attachmentUri]) so already-created backup files stay restorable; every other zip —
 *  any domain's newer :core:attachments bundle, populated via [VoxResult.attachmentUri] for
 *  domains with no legacy zip of their own, or [VoxResult.secondaryAttachmentUri] for Expenses,
 *  which already uses the primary field for receipts — is named `"$domain-attachments.zip"`. */
fun zipEntriesFor(domain: String, result: VoxResult): Map<String, String> = buildMap {
    result.attachmentUri?.let { uri ->
        put(if (domain == "expenses") "expenses-receipts.zip" else "$domain-attachments.zip", uri)
    }
    result.secondaryAttachmentUri?.let { uri ->
        put("$domain-attachments.zip", uri)
    }
}

suspend fun requestExportFor(context: Context, app: VoxAppInfo, config: AppBackupConfig): VoxResult? =
    VoxDataTransferClient.requestExport(
        context, app.packageName,
        scope = when {
            config.includeSettings && config.includeData -> VoxIpc.EXPORT_SCOPE_BOTH
            config.includeSettings -> VoxIpc.EXPORT_SCOPE_SETTINGS
            else -> VoxIpc.EXPORT_SCOPE_DATA
        },
        includeSecrets = config.includeApiKeys,
        includePhotos = config.includeAttachments,
        timeoutMs = if (config.includeAttachments) 30_000L else 10_000L
    )
