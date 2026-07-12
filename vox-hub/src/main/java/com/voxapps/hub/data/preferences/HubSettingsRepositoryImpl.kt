package com.voxapps.hub.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class HubSettingsRepositoryImpl(appContext: Context) : HubSettingsRepository {

    private val dataStore: DataStore<Preferences> = DataStoreProvider.get(appContext)

    private object Keys {
        val THEME_DARK_MODE = stringPreferencesKey("theme_dark_mode")
        val THEME_COLORED = booleanPreferencesKey("theme_colored")
    }

    override val settingsFlow: Flow<HubSettings> = dataStore.data.map { prefs ->
        HubSettings(
            themeDarkMode = prefs[Keys.THEME_DARK_MODE] ?: HubSettings.THEME_SYSTEM,
            themeColored = prefs[Keys.THEME_COLORED] ?: true
        )
    }

    override suspend fun setThemeDarkMode(mode: String) {
        dataStore.edit { it[Keys.THEME_DARK_MODE] = mode }
    }

    override suspend fun setThemeColored(colored: Boolean) {
        dataStore.edit { it[Keys.THEME_COLORED] = colored }
    }
}
