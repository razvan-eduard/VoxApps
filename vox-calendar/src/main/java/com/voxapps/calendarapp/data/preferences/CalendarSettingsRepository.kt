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
    suspend fun setAttachPhotoOnScan(enabled: Boolean)
    suspend fun setDebugLoggingEnabled(enabled: Boolean)
    suspend fun setDebugToastsEnabled(enabled: Boolean)
    suspend fun setThemeDarkMode(mode: String)
    suspend fun setThemeColored(colored: Boolean)
    suspend fun setOnboardingCompleted(completed: Boolean)
    suspend fun setIsGridView(enabled: Boolean)
    suspend fun setShowEventDetailsInWidget(enabled: Boolean)
    suspend fun setWidgetBorderEnabled(enabled: Boolean)
    suspend fun setWidgetBorderThicknessDp(thicknessDp: Int)
    suspend fun setWidgetBorderColorArgb(colorArgb: Long)
    suspend fun setTodayEffect(effect: String)
    suspend fun setTodayEffectStyle(style: String)
    suspend fun setTodayEffectColor(colorArgb: Long)
    suspend fun setTodayEffectColor2(colorArgb: Long?)
    suspend fun setTodayEffectSpeed(speed: Float)
    suspend fun setTodayEffectShowInWidget(enabled: Boolean)
    suspend fun setNotificationsSystemDefault(enabled: Boolean)
    suspend fun setNotificationsVibrationEnabled(enabled: Boolean)
    suspend fun setNotificationsSoundUri(uri: String?)
    suspend fun setNotificationsVolume(volume: Int)
    suspend fun setNotificationsLength(length: String)
    suspend fun setNotificationsChannelVersion(version: Int)

    /** Bulk overwrite, e.g. from a Vox Hub import — writes every portable field in one DataStore edit. */
    suspend fun restoreSettings(settings: CalendarSettings)
}
