package com.voxapps.notes.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.voxapps.design.effects.TodayEffect
import com.voxapps.design.effects.TodayEffectStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class NotesSettingsRepositoryImpl(appContext: Context) : NotesSettingsRepository {

    private val dataStore: DataStore<Preferences> = DataStoreProvider.get(appContext)

    private object Keys {
        val IS_BIOMETRIC_REQUIRED = booleanPreferencesKey("is_biometric_required")
        val SESSION_TIMEOUT_MINUTES = intPreferencesKey("session_timeout_minutes")
        val DEFAULT_VOICE_CATEGORY_ID = longPreferencesKey("default_voice_category_id")
        val VOICE_SAVE_TOAST_ENABLED = booleanPreferencesKey("voice_save_toast_enabled")
        val AUTO_CREATE_VOICE_CATEGORY = booleanPreferencesKey("auto_create_voice_category")
        val LANGUAGE = stringPreferencesKey("language")
        val SCHEDULED_MERGE_INTERVAL = stringPreferencesKey("scheduled_merge_interval")
        val SCHEDULED_NOTE_DEDUP_INTERVAL = stringPreferencesKey("scheduled_note_dedup_interval")
        val DEBUG_LOGGING_ENABLED = booleanPreferencesKey("debug_logging_enabled")
        val DEBUG_TOASTS_ENABLED = booleanPreferencesKey("debug_toasts_enabled")
        val CALENDAR_VIEW_ENABLED = booleanPreferencesKey("calendar_view_enabled")
        val IS_GRID_VIEW = booleanPreferencesKey("is_grid_view")
        val THEME_DARK_MODE = stringPreferencesKey("theme_dark_mode")
        val THEME_COLORED = booleanPreferencesKey("theme_colored")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val ATTACH_PHOTO_ON_SCAN = booleanPreferencesKey("attach_photo_on_scan")
        val SCAN_IMAGE_RETENTION = stringPreferencesKey("scan_image_retention")
        val TODAY_EFFECT = stringPreferencesKey("today_effect")
        val TODAY_EFFECT_STYLE = stringPreferencesKey("today_effect_style")
        val TODAY_EFFECT_COLOR = longPreferencesKey("today_effect_color")
        val TODAY_EFFECT_COLOR_2 = longPreferencesKey("today_effect_color_2")
        val TODAY_EFFECT_SPEED = floatPreferencesKey("today_effect_speed")
        val NOTIFICATIONS_SYSTEM_DEFAULT = booleanPreferencesKey("notifications_system_default")
        val NOTIFICATIONS_VIBRATION_ENABLED = booleanPreferencesKey("notifications_vibration_enabled")
        val NOTIFICATIONS_SOUND_URI = stringPreferencesKey("notifications_sound_uri")
        val NOTIFICATIONS_VOLUME = intPreferencesKey("notifications_volume")
        val NOTIFICATIONS_LENGTH = stringPreferencesKey("notifications_length")
        val NOTIFICATIONS_CHANNEL_VERSION = intPreferencesKey("notifications_channel_version")

        // Backup & Restore (local)
        val BACKUP_INCLUDE_SETTINGS = booleanPreferencesKey("backup_include_settings")
        val BACKUP_INCLUDE_DATA = booleanPreferencesKey("backup_include_data")
        val BACKUP_INCLUDE_ATTACHMENTS = booleanPreferencesKey("backup_include_attachments")
        val BACKUP_IMPORT_MODE = stringPreferencesKey("backup_import_mode")
    }

    override val settingsFlow: Flow<NotesSettings> = dataStore.data.map { prefs ->
        NotesSettings(
            isBiometricRequired = prefs[Keys.IS_BIOMETRIC_REQUIRED] ?: false,
            sessionTimeoutMinutes = prefs[Keys.SESSION_TIMEOUT_MINUTES] ?: NotesSettings.TIMEOUT_30M,
            defaultVoiceCategoryId = prefs[Keys.DEFAULT_VOICE_CATEGORY_ID],
            voiceSaveToastEnabled = prefs[Keys.VOICE_SAVE_TOAST_ENABLED] ?: false,
            autoCreateVoiceCategory = prefs[Keys.AUTO_CREATE_VOICE_CATEGORY] ?: false,
            language = prefs[Keys.LANGUAGE] ?: defaultDeviceLanguage(),
            scheduledMergeInterval = prefs[Keys.SCHEDULED_MERGE_INTERVAL] ?: NotesSettings.INTERVAL_OFF,
            scheduledNoteDedupInterval = prefs[Keys.SCHEDULED_NOTE_DEDUP_INTERVAL] ?: NotesSettings.INTERVAL_OFF,
            debugLoggingEnabled = prefs[Keys.DEBUG_LOGGING_ENABLED] ?: false,
            debugToastsEnabled = prefs[Keys.DEBUG_TOASTS_ENABLED] ?: false,
            calendarViewEnabled = prefs[Keys.CALENDAR_VIEW_ENABLED] ?: false,
            isGridView = prefs[Keys.IS_GRID_VIEW] ?: false,
            themeDarkMode = prefs[Keys.THEME_DARK_MODE] ?: NotesSettings.THEME_SYSTEM,
            themeColored = prefs[Keys.THEME_COLORED] ?: true,
            onboardingCompleted = prefs[Keys.ONBOARDING_COMPLETED] ?: false,
            attachPhotoOnScan = prefs[Keys.ATTACH_PHOTO_ON_SCAN] ?: false,
            scanImageRetention = prefs[Keys.SCAN_IMAGE_RETENTION] ?: NotesSettings.RETENTION_ON_FAILURE,
            todayEffect = prefs[Keys.TODAY_EFFECT] ?: TodayEffect.NONE.name,
            todayEffectStyle = prefs[Keys.TODAY_EFFECT_STYLE] ?: TodayEffectStyle.RING.name,
            todayEffectColor = prefs[Keys.TODAY_EFFECT_COLOR] ?: NotesSettings.TODAY_EFFECT_DEFAULT_COLOR,
            todayEffectColor2 = prefs[Keys.TODAY_EFFECT_COLOR_2],
            todayEffectSpeed = prefs[Keys.TODAY_EFFECT_SPEED] ?: 1f,
            notificationsSystemDefault = prefs[Keys.NOTIFICATIONS_SYSTEM_DEFAULT] ?: true,
            notificationsVibrationEnabled = prefs[Keys.NOTIFICATIONS_VIBRATION_ENABLED] ?: true,
            notificationsSoundUri = prefs[Keys.NOTIFICATIONS_SOUND_URI],
            notificationsVolume = prefs[Keys.NOTIFICATIONS_VOLUME] ?: 100,
            notificationsLength = prefs[Keys.NOTIFICATIONS_LENGTH] ?: NotesSettings.LENGTH_SHORT,
            notificationsChannelVersion = prefs[Keys.NOTIFICATIONS_CHANNEL_VERSION] ?: 1,
            backupIncludeSettings = prefs[Keys.BACKUP_INCLUDE_SETTINGS] ?: true,
            backupIncludeData = prefs[Keys.BACKUP_INCLUDE_DATA] ?: true,
            backupIncludeAttachments = prefs[Keys.BACKUP_INCLUDE_ATTACHMENTS] ?: false,
            backupImportMode = prefs[Keys.BACKUP_IMPORT_MODE] ?: "merge"
        )
    }

    @Volatile private var cachedSnapshot: NotesSettings? = null

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            settingsFlow.collect { cachedSnapshot = it }
        }
    }

    override fun getSnapshot(): NotesSettings =
        cachedSnapshot ?: runBlocking { settingsFlow.first() }.also { cachedSnapshot = it }

    override suspend fun setBiometricRequired(required: Boolean) {
        dataStore.edit { it[Keys.IS_BIOMETRIC_REQUIRED] = required }
    }

    override suspend fun setSessionTimeoutMinutes(minutes: Int) {
        dataStore.edit { it[Keys.SESSION_TIMEOUT_MINUTES] = minutes }
    }

    override suspend fun setDefaultVoiceCategoryId(id: Long?) {
        dataStore.edit {
            if (id == null) it.remove(Keys.DEFAULT_VOICE_CATEGORY_ID) else it[Keys.DEFAULT_VOICE_CATEGORY_ID] = id
        }
    }

    override suspend fun setVoiceSaveToastEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.VOICE_SAVE_TOAST_ENABLED] = enabled }
    }

    override suspend fun setAutoCreateVoiceCategory(enabled: Boolean) {
        dataStore.edit { it[Keys.AUTO_CREATE_VOICE_CATEGORY] = enabled }
    }

    override suspend fun setLanguage(code: String) {
        dataStore.edit { it[Keys.LANGUAGE] = code }
    }

    override suspend fun setScheduledMergeInterval(interval: String) {
        dataStore.edit { it[Keys.SCHEDULED_MERGE_INTERVAL] = interval }
    }

    override suspend fun setScheduledNoteDedupInterval(interval: String) {
        dataStore.edit { it[Keys.SCHEDULED_NOTE_DEDUP_INTERVAL] = interval }
    }

    override suspend fun setDebugLoggingEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.DEBUG_LOGGING_ENABLED] = enabled }
    }

    override suspend fun setDebugToastsEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.DEBUG_TOASTS_ENABLED] = enabled }
    }

    override suspend fun setCalendarViewEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.CALENDAR_VIEW_ENABLED] = enabled }
    }

    override suspend fun setIsGridView(enabled: Boolean) {
        dataStore.edit { it[Keys.IS_GRID_VIEW] = enabled }
    }

    override suspend fun setThemeDarkMode(mode: String) {
        dataStore.edit { it[Keys.THEME_DARK_MODE] = mode }
    }

    override suspend fun setThemeColored(colored: Boolean) {
        dataStore.edit { it[Keys.THEME_COLORED] = colored }
    }

    override suspend fun setOnboardingCompleted(completed: Boolean) {
        dataStore.edit { it[Keys.ONBOARDING_COMPLETED] = completed }
    }

    override suspend fun setAttachPhotoOnScan(enabled: Boolean) {
        dataStore.edit { it[Keys.ATTACH_PHOTO_ON_SCAN] = enabled }
    }

    override suspend fun setScanImageRetention(mode: String) {
        dataStore.edit { it[Keys.SCAN_IMAGE_RETENTION] = mode }
    }

    override suspend fun setTodayEffect(effect: String) {
        dataStore.edit { it[Keys.TODAY_EFFECT] = effect }
    }

    override suspend fun setTodayEffectStyle(style: String) {
        dataStore.edit { it[Keys.TODAY_EFFECT_STYLE] = style }
    }

    override suspend fun setTodayEffectColor(colorArgb: Long) {
        dataStore.edit { it[Keys.TODAY_EFFECT_COLOR] = colorArgb }
    }

    override suspend fun setTodayEffectColor2(colorArgb: Long?) {
        dataStore.edit {
            if (colorArgb == null) it.remove(Keys.TODAY_EFFECT_COLOR_2) else it[Keys.TODAY_EFFECT_COLOR_2] = colorArgb
        }
    }

    override suspend fun setTodayEffectSpeed(speed: Float) {
        dataStore.edit { it[Keys.TODAY_EFFECT_SPEED] = speed }
    }

    override suspend fun setNotificationsSystemDefault(enabled: Boolean) {
        dataStore.edit { it[Keys.NOTIFICATIONS_SYSTEM_DEFAULT] = enabled }
    }

    override suspend fun setNotificationsVibrationEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.NOTIFICATIONS_VIBRATION_ENABLED] = enabled }
    }

    override suspend fun setNotificationsSoundUri(uri: String?) {
        dataStore.edit {
            if (uri == null) it.remove(Keys.NOTIFICATIONS_SOUND_URI) else it[Keys.NOTIFICATIONS_SOUND_URI] = uri
        }
    }

    override suspend fun setNotificationsVolume(volume: Int) {
        dataStore.edit { it[Keys.NOTIFICATIONS_VOLUME] = volume }
    }

    override suspend fun setNotificationsLength(length: String) {
        dataStore.edit { it[Keys.NOTIFICATIONS_LENGTH] = length }
    }

    override suspend fun setNotificationsChannelVersion(version: Int) {
        dataStore.edit { it[Keys.NOTIFICATIONS_CHANNEL_VERSION] = version }
    }

    override suspend fun restoreSettings(settings: NotesSettings) {
        dataStore.edit { prefs ->
            prefs[Keys.IS_BIOMETRIC_REQUIRED] = settings.isBiometricRequired
            prefs[Keys.SESSION_TIMEOUT_MINUTES] = settings.sessionTimeoutMinutes
            if (settings.defaultVoiceCategoryId == null) {
                prefs.remove(Keys.DEFAULT_VOICE_CATEGORY_ID)
            } else {
                prefs[Keys.DEFAULT_VOICE_CATEGORY_ID] = settings.defaultVoiceCategoryId
            }
            prefs[Keys.VOICE_SAVE_TOAST_ENABLED] = settings.voiceSaveToastEnabled
            prefs[Keys.AUTO_CREATE_VOICE_CATEGORY] = settings.autoCreateVoiceCategory
            prefs[Keys.LANGUAGE] = settings.language
            prefs[Keys.SCHEDULED_MERGE_INTERVAL] = settings.scheduledMergeInterval
            prefs[Keys.SCHEDULED_NOTE_DEDUP_INTERVAL] = settings.scheduledNoteDedupInterval
            prefs[Keys.DEBUG_LOGGING_ENABLED] = settings.debugLoggingEnabled
            prefs[Keys.DEBUG_TOASTS_ENABLED] = settings.debugToastsEnabled
            prefs[Keys.CALENDAR_VIEW_ENABLED] = settings.calendarViewEnabled
            prefs[Keys.IS_GRID_VIEW] = settings.isGridView
            prefs[Keys.THEME_DARK_MODE] = settings.themeDarkMode
            prefs[Keys.THEME_COLORED] = settings.themeColored
            prefs[Keys.ATTACH_PHOTO_ON_SCAN] = settings.attachPhotoOnScan
            prefs[Keys.SCAN_IMAGE_RETENTION] = settings.scanImageRetention
            prefs[Keys.TODAY_EFFECT] = settings.todayEffect
            prefs[Keys.TODAY_EFFECT_STYLE] = settings.todayEffectStyle
            prefs[Keys.TODAY_EFFECT_COLOR] = settings.todayEffectColor
            if (settings.todayEffectColor2 == null) {
                prefs.remove(Keys.TODAY_EFFECT_COLOR_2)
            } else {
                prefs[Keys.TODAY_EFFECT_COLOR_2] = settings.todayEffectColor2
            }
            prefs[Keys.TODAY_EFFECT_SPEED] = settings.todayEffectSpeed
            prefs[Keys.NOTIFICATIONS_SYSTEM_DEFAULT] = settings.notificationsSystemDefault
            prefs[Keys.NOTIFICATIONS_VIBRATION_ENABLED] = settings.notificationsVibrationEnabled
            if (settings.notificationsSoundUri == null) {
                prefs.remove(Keys.NOTIFICATIONS_SOUND_URI)
            } else {
                prefs[Keys.NOTIFICATIONS_SOUND_URI] = settings.notificationsSoundUri
            }
            prefs[Keys.NOTIFICATIONS_VOLUME] = settings.notificationsVolume
            prefs[Keys.NOTIFICATIONS_LENGTH] = settings.notificationsLength
            prefs[Keys.NOTIFICATIONS_CHANNEL_VERSION] = settings.notificationsChannelVersion
            prefs[Keys.BACKUP_INCLUDE_SETTINGS] = settings.backupIncludeSettings
            prefs[Keys.BACKUP_INCLUDE_DATA] = settings.backupIncludeData
            prefs[Keys.BACKUP_INCLUDE_ATTACHMENTS] = settings.backupIncludeAttachments
            prefs[Keys.BACKUP_IMPORT_MODE] = settings.backupImportMode
        }
    }

    override suspend fun setBackupIncludeSettings(enabled: Boolean) {
        dataStore.edit { it[Keys.BACKUP_INCLUDE_SETTINGS] = enabled }
    }

    override suspend fun setBackupIncludeData(enabled: Boolean) {
        dataStore.edit { it[Keys.BACKUP_INCLUDE_DATA] = enabled }
    }

    override suspend fun setBackupIncludeAttachments(enabled: Boolean) {
        dataStore.edit { it[Keys.BACKUP_INCLUDE_ATTACHMENTS] = enabled }
    }

    override suspend fun setBackupImportMode(mode: String) {
        dataStore.edit { it[Keys.BACKUP_IMPORT_MODE] = mode }
    }

    private fun defaultDeviceLanguage(): String =
        java.util.Locale.getDefault().language.ifBlank { NotesSettings.DEFAULT_LANGUAGE }
}
