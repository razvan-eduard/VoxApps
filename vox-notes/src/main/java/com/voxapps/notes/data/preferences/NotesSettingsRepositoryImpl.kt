package com.voxapps.notes.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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
        val CALENDAR_VIEW_ENABLED = booleanPreferencesKey("calendar_view_enabled")
        val THEME_DARK_MODE = stringPreferencesKey("theme_dark_mode")
        val THEME_COLORED = booleanPreferencesKey("theme_colored")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
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
            calendarViewEnabled = prefs[Keys.CALENDAR_VIEW_ENABLED] ?: false,
            themeDarkMode = prefs[Keys.THEME_DARK_MODE] ?: NotesSettings.THEME_SYSTEM,
            themeColored = prefs[Keys.THEME_COLORED] ?: true,
            onboardingCompleted = prefs[Keys.ONBOARDING_COMPLETED] ?: false
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

    override suspend fun setCalendarViewEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.CALENDAR_VIEW_ENABLED] = enabled }
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
            prefs[Keys.CALENDAR_VIEW_ENABLED] = settings.calendarViewEnabled
            prefs[Keys.THEME_DARK_MODE] = settings.themeDarkMode
            prefs[Keys.THEME_COLORED] = settings.themeColored
        }
    }

    private fun defaultDeviceLanguage(): String =
        java.util.Locale.getDefault().language.ifBlank { NotesSettings.DEFAULT_LANGUAGE }
}
