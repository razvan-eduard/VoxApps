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
    suspend fun setDebugToastsEnabled(enabled: Boolean)
    suspend fun setCalendarViewEnabled(enabled: Boolean)
    suspend fun setIsGridView(enabled: Boolean)
    suspend fun setAttachPhotoOnScan(enabled: Boolean)
    suspend fun setScanImageRetention(mode: String)
    suspend fun setThemeDarkMode(mode: String)
    suspend fun setThemeColored(colored: Boolean)
    suspend fun setOnboardingCompleted(completed: Boolean)
    suspend fun setNotificationsSystemDefault(enabled: Boolean)
    suspend fun setNotificationsVibrationEnabled(enabled: Boolean)
    suspend fun setNotificationsSoundUri(uri: String?)
    suspend fun setNotificationsVolume(volume: Int)
    suspend fun setNotificationsLength(length: String)
    suspend fun setNotificationsChannelVersion(version: Int)

    /** Bulk overwrite, e.g. from a Vox Hub import — writes every field in one DataStore edit. */
    suspend fun restoreSettings(settings: NotesSettings)
}
