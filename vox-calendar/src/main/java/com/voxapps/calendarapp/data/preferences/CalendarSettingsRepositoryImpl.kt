package com.voxapps.calendarapp.data.preferences

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

class CalendarSettingsRepositoryImpl(appContext: Context) : CalendarSettingsRepository {

    private val dataStore: DataStore<Preferences> = DataStoreProvider.get(appContext)

    private object Keys {
        val IS_BIOMETRIC_REQUIRED = booleanPreferencesKey("is_biometric_required")
        val SESSION_TIMEOUT_MINUTES = intPreferencesKey("session_timeout_minutes")
        val LANGUAGE = stringPreferencesKey("language")
        val DEFAULT_LAYER_ID = longPreferencesKey("default_layer_id")
        val AUTO_CREATE_LAYER = booleanPreferencesKey("auto_create_layer")
        val DEBUG_LOGGING_ENABLED = booleanPreferencesKey("debug_logging_enabled")
        val THEME_DARK_MODE = stringPreferencesKey("theme_dark_mode")
        val THEME_COLORED = booleanPreferencesKey("theme_colored")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
    }

    override val settingsFlow: Flow<CalendarSettings> = dataStore.data.map { prefs ->
        CalendarSettings(
            isBiometricRequired = prefs[Keys.IS_BIOMETRIC_REQUIRED] ?: false,
            sessionTimeoutMinutes = prefs[Keys.SESSION_TIMEOUT_MINUTES] ?: CalendarSettings.TIMEOUT_30M,
            language = prefs[Keys.LANGUAGE] ?: defaultDeviceLanguage(),
            defaultLayerId = prefs[Keys.DEFAULT_LAYER_ID],
            autoCreateLayer = prefs[Keys.AUTO_CREATE_LAYER] ?: false,
            debugLoggingEnabled = prefs[Keys.DEBUG_LOGGING_ENABLED] ?: false,
            themeDarkMode = prefs[Keys.THEME_DARK_MODE] ?: CalendarSettings.THEME_SYSTEM,
            themeColored = prefs[Keys.THEME_COLORED] ?: true,
            onboardingCompleted = prefs[Keys.ONBOARDING_COMPLETED] ?: false
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

    override suspend fun setDebugLoggingEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.DEBUG_LOGGING_ENABLED] = enabled }
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
            prefs[Keys.DEBUG_LOGGING_ENABLED] = settings.debugLoggingEnabled
            prefs[Keys.THEME_DARK_MODE] = settings.themeDarkMode
            prefs[Keys.THEME_COLORED] = settings.themeColored
        }
    }

    private fun defaultDeviceLanguage(): String =
        java.util.Locale.getDefault().language.ifBlank { CalendarSettings.DEFAULT_LANGUAGE }
}
