package com.voxapps.calendarapp.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.voxapps.design.color.VoxColorPalette
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

class CalendarSettingsRepositoryImpl(appContext: Context) : CalendarSettingsRepository {

    private val dataStore: DataStore<Preferences> = DataStoreProvider.get(appContext)

    private object Keys {
        val IS_BIOMETRIC_REQUIRED = booleanPreferencesKey("is_biometric_required")
        val SESSION_TIMEOUT_MINUTES = intPreferencesKey("session_timeout_minutes")
        val LANGUAGE = stringPreferencesKey("language")
        val DEFAULT_LAYER_ID = longPreferencesKey("default_layer_id")
        val AUTO_CREATE_LAYER = booleanPreferencesKey("auto_create_layer")
        val ATTACH_PHOTO_ON_SCAN = booleanPreferencesKey("attach_photo_on_scan")
        val SCAN_LLM_LEVEL = stringPreferencesKey("scan_llm_level")
        val VOICE_LLM_LEVEL = stringPreferencesKey("voice_llm_level")
        val DEBUG_LOGGING_ENABLED = booleanPreferencesKey("debug_logging_enabled")
        val DEBUG_TOASTS_ENABLED = booleanPreferencesKey("debug_toasts_enabled")
        val THEME_DARK_MODE = stringPreferencesKey("theme_dark_mode")
        val THEME_COLORED = booleanPreferencesKey("theme_colored")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")

        // Backup & Restore (local)
        val BACKUP_INCLUDE_SETTINGS = booleanPreferencesKey("backup_include_settings")
        val BACKUP_INCLUDE_DATA = booleanPreferencesKey("backup_include_data")
        val BACKUP_INCLUDE_ATTACHMENTS = booleanPreferencesKey("backup_include_attachments")
        val BACKUP_IMPORT_MODE = stringPreferencesKey("backup_import_mode")
        val IS_GRID_VIEW = booleanPreferencesKey("is_grid_view")
        val SHOW_EVENT_DETAILS_IN_WIDGET = booleanPreferencesKey("show_event_details_in_widget")
        val WIDGET_BORDER_ENABLED = booleanPreferencesKey("widget_border_enabled")
        val WIDGET_BORDER_THICKNESS_DP = intPreferencesKey("widget_border_thickness_dp")
        val WIDGET_BORDER_COLOR_ARGB = longPreferencesKey("widget_border_color_argb")
        val TODAY_EFFECT = stringPreferencesKey("today_effect")
        val TODAY_EFFECT_STYLE = stringPreferencesKey("today_effect_style")
        val TODAY_EFFECT_COLOR = longPreferencesKey("today_effect_color")
        val TODAY_EFFECT_COLOR_2 = longPreferencesKey("today_effect_color_2")
        val TODAY_EFFECT_SPEED = floatPreferencesKey("today_effect_speed")
        val TODAY_EFFECT_SHOW_IN_WIDGET = booleanPreferencesKey("today_effect_show_in_widget")
        val NOTIFICATIONS_SYSTEM_DEFAULT = booleanPreferencesKey("notifications_system_default")
        val NOTIFICATIONS_VIBRATION_ENABLED = booleanPreferencesKey("notifications_vibration_enabled")
        val NOTIFICATIONS_SOUND_URI = stringPreferencesKey("notifications_sound_uri")
        val NOTIFICATIONS_VOLUME = intPreferencesKey("notifications_volume")
        val NOTIFICATIONS_LENGTH = stringPreferencesKey("notifications_length")
        val NOTIFICATIONS_CHANNEL_VERSION = intPreferencesKey("notifications_channel_version")
        val TODO_BLEED_TO_CALENDAR = booleanPreferencesKey("todo_bleed_to_calendar")
        val FIELD_CORRECTION_MEMORY_ENABLED = booleanPreferencesKey("field_correction_memory_enabled")
        val FIELD_CORRECTION_MEMORY_THRESHOLD = intPreferencesKey("field_correction_memory_threshold")
        val ANIMATIONS_ENABLED = booleanPreferencesKey("animations_enabled")
    }

    override val settingsFlow: Flow<CalendarSettings> = dataStore.data.map { prefs ->
        CalendarSettings(
            isBiometricRequired = prefs[Keys.IS_BIOMETRIC_REQUIRED] ?: false,
            sessionTimeoutMinutes = prefs[Keys.SESSION_TIMEOUT_MINUTES] ?: CalendarSettings.TIMEOUT_30M,
            language = prefs[Keys.LANGUAGE] ?: defaultDeviceLanguage(),
            defaultLayerId = prefs[Keys.DEFAULT_LAYER_ID],
            autoCreateLayer = prefs[Keys.AUTO_CREATE_LAYER] ?: false,
            attachPhotoOnScan = prefs[Keys.ATTACH_PHOTO_ON_SCAN] ?: false,
            scanLlmLevel = prefs[Keys.SCAN_LLM_LEVEL] ?: CalendarSettings.SCAN_FLOW_SUPPORT.default.name,
            voiceLlmLevel = prefs[Keys.VOICE_LLM_LEVEL] ?: CalendarSettings.VOICE_FLOW_SUPPORT.default.name,
            debugLoggingEnabled = prefs[Keys.DEBUG_LOGGING_ENABLED] ?: false,
            debugToastsEnabled = prefs[Keys.DEBUG_TOASTS_ENABLED] ?: false,
            themeDarkMode = prefs[Keys.THEME_DARK_MODE] ?: CalendarSettings.THEME_SYSTEM,
            themeColored = prefs[Keys.THEME_COLORED] ?: true,
            onboardingCompleted = prefs[Keys.ONBOARDING_COMPLETED] ?: false,
            backupIncludeSettings = prefs[Keys.BACKUP_INCLUDE_SETTINGS] ?: true,
            backupIncludeData = prefs[Keys.BACKUP_INCLUDE_DATA] ?: true,
            backupIncludeAttachments = prefs[Keys.BACKUP_INCLUDE_ATTACHMENTS] ?: false,
            backupImportMode = prefs[Keys.BACKUP_IMPORT_MODE] ?: "merge",
            isGridView = prefs[Keys.IS_GRID_VIEW] ?: false,
            showEventDetailsInWidget = prefs[Keys.SHOW_EVENT_DETAILS_IN_WIDGET] ?: true,
            widgetBorderEnabled = prefs[Keys.WIDGET_BORDER_ENABLED] ?: true,
            widgetBorderThicknessDp = prefs[Keys.WIDGET_BORDER_THICKNESS_DP] ?: CalendarSettings.THICKNESS_MEDIUM,
            widgetBorderColorArgb = prefs[Keys.WIDGET_BORDER_COLOR_ARGB] ?: VoxColorPalette.presets.first(),
            todayEffect = prefs[Keys.TODAY_EFFECT] ?: TodayEffect.NONE.name,
            todayEffectStyle = prefs[Keys.TODAY_EFFECT_STYLE] ?: TodayEffectStyle.RING.name,
            todayEffectColor = prefs[Keys.TODAY_EFFECT_COLOR] ?: CalendarSettings.TODAY_EFFECT_DEFAULT_COLOR,
            todayEffectColor2 = prefs[Keys.TODAY_EFFECT_COLOR_2],
            todayEffectSpeed = prefs[Keys.TODAY_EFFECT_SPEED] ?: 1f,
            todayEffectShowInWidget = prefs[Keys.TODAY_EFFECT_SHOW_IN_WIDGET] ?: true,
            notificationsSystemDefault = prefs[Keys.NOTIFICATIONS_SYSTEM_DEFAULT] ?: true,
            notificationsVibrationEnabled = prefs[Keys.NOTIFICATIONS_VIBRATION_ENABLED] ?: true,
            notificationsSoundUri = prefs[Keys.NOTIFICATIONS_SOUND_URI],
            notificationsVolume = prefs[Keys.NOTIFICATIONS_VOLUME] ?: 100,
            notificationsLength = prefs[Keys.NOTIFICATIONS_LENGTH] ?: CalendarSettings.LENGTH_SHORT,
            notificationsChannelVersion = prefs[Keys.NOTIFICATIONS_CHANNEL_VERSION] ?: 1,
            todoBleedToCalendar = prefs[Keys.TODO_BLEED_TO_CALENDAR] ?: true,
            fieldCorrectionMemoryEnabled = prefs[Keys.FIELD_CORRECTION_MEMORY_ENABLED] ?: false,
            fieldCorrectionThreshold = prefs[Keys.FIELD_CORRECTION_MEMORY_THRESHOLD]
                ?: CalendarSettings.CORRECTION_SPEED_MEDIUM,
            animationsEnabled = prefs[Keys.ANIMATIONS_ENABLED] ?: true
        )
    }

    @Volatile private var cachedSnapshot: CalendarSettings? = null

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            settingsFlow.collect { cachedSnapshot = it }
        }
    }

    override fun getSnapshot(): CalendarSettings =
        cachedSnapshot ?: runBlocking { settingsFlow.first() }.also { cachedSnapshot = it }

    override suspend fun setBiometricRequired(required: Boolean) {
        dataStore.edit { it[Keys.IS_BIOMETRIC_REQUIRED] = required }
    }

    override suspend fun setSessionTimeoutMinutes(minutes: Int) {
        dataStore.edit { it[Keys.SESSION_TIMEOUT_MINUTES] = minutes }
    }

    override suspend fun setLanguage(code: String) {
        dataStore.edit { it[Keys.LANGUAGE] = code }
    }

    override suspend fun setDefaultLayerId(id: Long?) {
        dataStore.edit {
            if (id == null) it.remove(Keys.DEFAULT_LAYER_ID) else it[Keys.DEFAULT_LAYER_ID] = id
        }
    }

    override suspend fun setAutoCreateLayer(enabled: Boolean) {
        dataStore.edit { it[Keys.AUTO_CREATE_LAYER] = enabled }
    }

    override suspend fun setAttachPhotoOnScan(enabled: Boolean) {
        dataStore.edit { it[Keys.ATTACH_PHOTO_ON_SCAN] = enabled }
    }

    override suspend fun setScanLlmLevel(level: String) {
        if (CalendarSettings.SCAN_FLOW_SUPPORT.supported.none { it.name == level }) return
        dataStore.edit { it[Keys.SCAN_LLM_LEVEL] = level }
    }

    override suspend fun setVoiceLlmLevel(level: String) {
        if (CalendarSettings.VOICE_FLOW_SUPPORT.supported.none { it.name == level }) return
        dataStore.edit { it[Keys.VOICE_LLM_LEVEL] = level }
    }

    override suspend fun setDebugLoggingEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.DEBUG_LOGGING_ENABLED] = enabled }
    }

    override suspend fun setDebugToastsEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.DEBUG_TOASTS_ENABLED] = enabled }
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

    override suspend fun setIsGridView(enabled: Boolean) {
        dataStore.edit { it[Keys.IS_GRID_VIEW] = enabled }
    }

    override suspend fun setShowEventDetailsInWidget(enabled: Boolean) {
        dataStore.edit { it[Keys.SHOW_EVENT_DETAILS_IN_WIDGET] = enabled }
    }

    override suspend fun setWidgetBorderEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.WIDGET_BORDER_ENABLED] = enabled }
    }

    override suspend fun setWidgetBorderThicknessDp(thicknessDp: Int) {
        dataStore.edit { it[Keys.WIDGET_BORDER_THICKNESS_DP] = thicknessDp }
    }

    override suspend fun setWidgetBorderColorArgb(colorArgb: Long) {
        dataStore.edit { it[Keys.WIDGET_BORDER_COLOR_ARGB] = colorArgb }
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

    override suspend fun setTodayEffectShowInWidget(enabled: Boolean) {
        dataStore.edit { it[Keys.TODAY_EFFECT_SHOW_IN_WIDGET] = enabled }
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

    override suspend fun setTodoBleedToCalendar(enabled: Boolean) {
        dataStore.edit { it[Keys.TODO_BLEED_TO_CALENDAR] = enabled }
    }

    override suspend fun setFieldCorrectionMemoryEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.FIELD_CORRECTION_MEMORY_ENABLED] = enabled }
    }

    override suspend fun setFieldCorrectionThreshold(count: Int) {
        dataStore.edit { it[Keys.FIELD_CORRECTION_MEMORY_THRESHOLD] = count }
    }

    override suspend fun setAnimationsEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.ANIMATIONS_ENABLED] = enabled }
    }

    override suspend fun restoreSettings(settings: CalendarSettings) {
        dataStore.edit { prefs ->
            prefs[Keys.IS_BIOMETRIC_REQUIRED] = settings.isBiometricRequired
            prefs[Keys.SESSION_TIMEOUT_MINUTES] = settings.sessionTimeoutMinutes
            prefs[Keys.LANGUAGE] = settings.language
            if (settings.defaultLayerId == null) {
                prefs.remove(Keys.DEFAULT_LAYER_ID)
            } else {
                prefs[Keys.DEFAULT_LAYER_ID] = settings.defaultLayerId
            }
            prefs[Keys.AUTO_CREATE_LAYER] = settings.autoCreateLayer
            prefs[Keys.ATTACH_PHOTO_ON_SCAN] = settings.attachPhotoOnScan
            prefs[Keys.SCAN_LLM_LEVEL] = settings.scanLlmLevel
            prefs[Keys.VOICE_LLM_LEVEL] = settings.voiceLlmLevel
            prefs[Keys.DEBUG_LOGGING_ENABLED] = settings.debugLoggingEnabled
            prefs[Keys.DEBUG_TOASTS_ENABLED] = settings.debugToastsEnabled
            prefs[Keys.THEME_DARK_MODE] = settings.themeDarkMode
            prefs[Keys.THEME_COLORED] = settings.themeColored
            prefs[Keys.IS_GRID_VIEW] = settings.isGridView
            prefs[Keys.SHOW_EVENT_DETAILS_IN_WIDGET] = settings.showEventDetailsInWidget
            prefs[Keys.WIDGET_BORDER_ENABLED] = settings.widgetBorderEnabled
            prefs[Keys.WIDGET_BORDER_THICKNESS_DP] = settings.widgetBorderThicknessDp
            prefs[Keys.WIDGET_BORDER_COLOR_ARGB] = settings.widgetBorderColorArgb
            prefs[Keys.TODAY_EFFECT] = settings.todayEffect
            prefs[Keys.TODAY_EFFECT_STYLE] = settings.todayEffectStyle
            prefs[Keys.TODAY_EFFECT_COLOR] = settings.todayEffectColor
            if (settings.todayEffectColor2 == null) {
                prefs.remove(Keys.TODAY_EFFECT_COLOR_2)
            } else {
                prefs[Keys.TODAY_EFFECT_COLOR_2] = settings.todayEffectColor2
            }
            prefs[Keys.TODAY_EFFECT_SPEED] = settings.todayEffectSpeed
            prefs[Keys.TODAY_EFFECT_SHOW_IN_WIDGET] = settings.todayEffectShowInWidget
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
            prefs[Keys.TODO_BLEED_TO_CALENDAR] = settings.todoBleedToCalendar
            prefs[Keys.FIELD_CORRECTION_MEMORY_ENABLED] = settings.fieldCorrectionMemoryEnabled
            prefs[Keys.FIELD_CORRECTION_MEMORY_THRESHOLD] = settings.fieldCorrectionThreshold
            prefs[Keys.ANIMATIONS_ENABLED] = settings.animationsEnabled
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
        java.util.Locale.getDefault().language.ifBlank { CalendarSettings.DEFAULT_LANGUAGE }
}
