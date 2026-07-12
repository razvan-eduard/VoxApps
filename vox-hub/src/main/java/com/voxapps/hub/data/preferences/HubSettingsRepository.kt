package com.voxapps.hub.data.preferences

import kotlinx.coroutines.flow.Flow

/**
 * Single source of truth for persisted Vox Hub settings. DataStore-backed (mirrors vox-notes'
 * NotesSettingsRepository, scaled down since Hub only persists theme preference so far).
 */
interface HubSettingsRepository {
    val settingsFlow: Flow<HubSettings>

    suspend fun setThemeDarkMode(mode: String)
    suspend fun setThemeColored(colored: Boolean)
}
