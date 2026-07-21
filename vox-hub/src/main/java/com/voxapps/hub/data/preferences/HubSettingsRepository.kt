package com.voxapps.hub.data.preferences

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

    /** Called by [com.voxapps.hub.domain.backup.BackupWorker] when a scheduled run finishes,
     *  success or failure — this is the only way the user learns a background run failed. */
    suspend fun recordBackupResult(success: Boolean, timestampMillis: Long, error: String?)
}
