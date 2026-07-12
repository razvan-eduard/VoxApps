package com.voxapps.calendarapp.data.preferences

import kotlinx.coroutines.flow.Flow

/**
 * Single source of truth for persisted Vox Calendar settings. DataStore-backed; CalendarStateManager
 * observes [settingsFlow] and combines it with runtime state (mirrors vox-expenses).
 */
interface CalendarSettingsRepository {
    val settingsFlow: Flow<CalendarSettings>

    /** Warm-cache synchronous read for non-coroutine consumers (e.g. an IPC receiver). */
    fun getSnapshot(): CalendarSettings

    suspend fun setBiometricRequired(required: Boolean)
    suspend fun setSessionTimeoutMinutes(minutes: Int)
    suspend fun setLanguage(code: String)
    suspend fun setDefaultLayerId(id: Long?)
    suspend fun setAutoCreateLayer(enabled: Boolean)
    suspend fun setDebugLoggingEnabled(enabled: Boolean)
    suspend fun setThemeDarkMode(mode: String)
    suspend fun setThemeColored(colored: Boolean)

    /** Bulk overwrite, e.g. from a Vox Hub import — writes every portable field in one DataStore edit. */
    suspend fun restoreSettings(settings: CalendarSettings)
}
