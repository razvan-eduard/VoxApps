package com.voxapps.notes.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
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
    }

    override val settingsFlow: Flow<NotesSettings> = dataStore.data.map { prefs ->
        NotesSettings(
            isBiometricRequired = prefs[Keys.IS_BIOMETRIC_REQUIRED] ?: false,
            sessionTimeoutMinutes = prefs[Keys.SESSION_TIMEOUT_MINUTES] ?: NotesSettings.TIMEOUT_30M,
            defaultVoiceCategoryId = prefs[Keys.DEFAULT_VOICE_CATEGORY_ID],
            voiceSaveToastEnabled = prefs[Keys.VOICE_SAVE_TOAST_ENABLED] ?: false
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
}
