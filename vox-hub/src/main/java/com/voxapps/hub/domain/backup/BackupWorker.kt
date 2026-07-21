package com.voxapps.hub.domain.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.voxapps.hub.HubApplication
import com.voxapps.hub.data.preferences.HubSettings
import com.voxapps.hub.data.preferences.HubSettingsRepository
import com.voxapps.ipc.VoxAppsDiscovery
import com.voxapps.ipc.VoxDataTransferClient
import com.voxapps.ipc.VoxIpc
import com.voxapps.logging.Logger
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "BackupWorker"

/** Safety margin checked before writing — well above a typical export's size, just enough to
 *  reliably distinguish "genuinely full" from "has room". */
private const val MIN_FREE_BYTES = 20L * 1024 * 1024

/**
 * Scheduled counterpart of Hub's manual "Export" button. Runs unattended, so unlike the
 * interactive flow it can't use the SAF picker or prompt the user to flash-retry an unreachable
 * app (that requires briefly foregrounding each one, which a background Worker can't do) — an
 * unreachable app is simply skipped for this run rather than retried. Every outcome (success or
 * why it failed) is recorded via [HubSettingsRepository.recordBackupResult] since this is the only
 * way the user finds out — see the failure banner in [com.voxapps.hub.ui.HubScreen].
 */
class BackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settingsRepo = (applicationContext as HubApplication).container.settingsRepository
        val backupsDir = File(applicationContext.getExternalFilesDir(null), "backups")
        backupsDir.mkdirs()

        try {
            // A retention count lowered since the last run might not have been enforced yet —
            // prune first so a stale excess of old backups doesn't itself cause a false
            // "insufficient storage" below.
            pruneOldBackups(backupsDir, settingsRepo.getSnapshot().backupRetentionCount)

            if (backupsDir.usableSpace < MIN_FREE_BYTES) {
                recordFailure(settingsRepo, "insufficient storage")
                return Result.success()
            }

            val apps = VoxAppsDiscovery.discover(applicationContext).filter { it.actions.contains("export") }
            val perDomainJson = mutableMapOf<String, String>()
            var attachmentUri: String? = null
            for (app in apps) {
                val reachable = VoxAppsDiscovery.ping(applicationContext, app.packageName, timeoutMs = 8_000L)
                if (!reachable) {
                    Logger.w(TAG, "Skipping unreachable app for scheduled backup: ${app.packageName}")
                    continue
                }
                val domain = app.domain ?: app.packageName
                val result = VoxDataTransferClient.requestExport(
                    applicationContext, app.packageName, VoxIpc.EXPORT_SCOPE_BOTH,
                    includeSecrets = false, includePhotos = false, timeoutMs = 10_000L
                )
                if (result != null && result.ok) {
                    perDomainJson[domain] = result.text
                    if (domain == "expenses") result.attachmentUri?.let { attachmentUri = it }
                } else {
                    Logger.w(TAG, "Export failed for ${app.packageName}: ${result?.text}")
                }
            }

            if (perDomainJson.isEmpty()) {
                recordFailure(settingsRepo, "no apps responded")
                return Result.success()
            }

            val fileName = "vox-backup-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.zip"
            val outFile = File(backupsDir, fileName)
            try {
                FileOutputStream(outFile).use { out ->
                    BackupZipWriter.write(out, applicationContext.contentResolver, perDomainJson, attachmentUri)
                }
            } catch (e: IOException) {
                outFile.delete()
                recordFailure(settingsRepo, e.message ?: "write failed")
                return Result.success()
            }

            pruneOldBackups(backupsDir, settingsRepo.getSnapshot().backupRetentionCount)
            settingsRepo.recordBackupResult(success = true, timestampMillis = System.currentTimeMillis(), error = null)
        } catch (e: Exception) {
            Logger.e(TAG, "Scheduled backup failed", e)
            recordFailure(settingsRepo, e.message ?: "unknown error")
        }
        return Result.success()
    }

    private suspend fun recordFailure(settingsRepo: HubSettingsRepository, reason: String) {
        settingsRepo.recordBackupResult(success = false, timestampMillis = System.currentTimeMillis(), error = reason)
    }

    companion object {
        /** [retentionCount] = [HubSettings.RETENTION_UNLIMITED] skips pruning entirely. Filenames
         *  sort chronologically (`vox-backup-yyyyMMdd-HHmmss.zip`), so the oldest excess files are
         *  just the first N once sorted. */
        fun pruneOldBackups(dir: File, retentionCount: Int) {
            if (retentionCount == HubSettings.RETENTION_UNLIMITED) return
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".zip") }?.sortedBy { it.name } ?: return
            val excess = files.size - retentionCount
            if (excess > 0) files.take(excess).forEach { it.delete() }
        }
    }
}
