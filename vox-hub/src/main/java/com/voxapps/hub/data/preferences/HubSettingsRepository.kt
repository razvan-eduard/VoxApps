package com.voxapps.hub.data.preferences

import com.voxapps.hub.domain.backup.AppBackupConfig
import kotlinx.coroutines.flow.Flow

/**
 * Single source of truth for persisted Vox Hub settings. DataStore-backed (mirrors vox-notes'
 * NotesSettingsRepository).
 */
interface HubSettingsRepository {
    val settingsFlow: Flow<HubSettings>

    /** Warm-cache synchronous read for non-coroutine consumers (e.g. Application.onCreate's
     *  WorkManager reschedule-on-start, mirrors NotesSettingsRepository's getSnapshot()). */
    fun getSnapshot(): HubSettings

    suspend fun setThemeDarkMode(mode: String)
    suspend fun setThemeColored(colored: Boolean)
    suspend fun setDebugLoggingEnabled(enabled: Boolean)
    suspend fun setDebugToastsEnabled(enabled: Boolean)
    suspend fun setBackupInterval(interval: String)
    suspend fun setBackupRetentionCount(count: Int)

    /** One of [HubSettings.IMPORT_MODE_FULL_OVERRIDE]/[HubSettings.IMPORT_MODE_MERGE]/
     *  [HubSettings.IMPORT_MODE_ADDITIVE] — see [HubSettings.importMode]'s doc comment. */
    suspend fun setImportMode(mode: String)

    /** Updates one app's backup config, leaving every other app's entry untouched — read-modify-write
     *  within a single DataStore edit so concurrent toggles from different app sections don't race. */
    suspend fun setAppBackupConfig(packageName: String, config: AppBackupConfig)

    /** Called by [com.voxapps.hub.domain.backup.BackupWorker] when a scheduled run finishes,
     *  success or failure — this is the only way the user learns a background run failed.
     *  [missingApps] is non-empty when the run produced a zip but one or more apps didn't make it
     *  in (never woke up, or their export failed) — see [HubSettings.lastBackupMissingApps]. */
    suspend fun recordBackupResult(
        success: Boolean,
        timestampMillis: Long,
        error: String?,
        missingApps: List<String> = emptyList()
    )

    suspend fun setVoxConnectEnabled(enabled: Boolean)
    suspend fun setVoxConnectPort(port: Int)
    suspend fun setVoxConnectMediaControlEnabled(enabled: Boolean)

    /** Updates one domain's monitored flag, leaving every other domain's entry untouched — same
     *  read-modify-write shape as [setAppBackupConfig]. */
    suspend fun setVoxConnectMonitoredApp(domain: String, monitored: Boolean)

    /** Bulk overwrite from Hub's own local Backup & Restore card — writes every portable field in
     *  one DataStore edit (mirrors every satellite app's identical `restoreSettings`). Device-local
     *  runtime state ([HubSettings.lastBackupSuccess] and friends, [HubSettings.voxConnectEnabled]/
     *  [HubSettings.voxConnectPort]) is deliberately never part of [settings] here — see
     *  `HubBackupSettingsSection`'s export-side exclusion of the same fields. */
    suspend fun restoreSettings(settings: HubSettings)
}
