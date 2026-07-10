package com.voxapps.notes.data.preferences

import kotlinx.coroutines.flow.Flow

/**
 * Single source of truth for persisted VoxNotes settings. DataStore-backed; NotesStateManager
 * observes [settingsFlow] and combines it with runtime state (mirrors vox-commander).
 */
interface NotesSettingsRepository {
    val settingsFlow: Flow<NotesSettings>

    /** Warm-cache synchronous read for non-coroutine consumers (e.g. the IPC receiver). */
    fun getSnapshot(): NotesSettings

    suspend fun setBiometricRequired(required: Boolean)
    suspend fun setSessionTimeoutMinutes(minutes: Int)
    suspend fun setDefaultVoiceCategoryId(id: Long?)
    suspend fun setVoiceSaveToastEnabled(enabled: Boolean)
    suspend fun setAutoCreateVoiceCategory(enabled: Boolean)
    suspend fun setLanguage(code: String)
    suspend fun setScheduledMergeInterval(interval: String)
    suspend fun setScheduledNoteDedupInterval(interval: String)
    suspend fun setDebugLoggingEnabled(enabled: Boolean)
}
