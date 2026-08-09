package com.voxapps.hub.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.voxapps.hub.domain.backup.AppBackupConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

class HubSettingsRepositoryImpl(appContext: Context) : HubSettingsRepository {

    private val dataStore: DataStore<Preferences> = DataStoreProvider.get(appContext)

    private object Keys {
        val THEME_DARK_MODE = stringPreferencesKey("theme_dark_mode")
        val THEME_COLORED = booleanPreferencesKey("theme_colored")
        val DEBUG_LOGGING_ENABLED = booleanPreferencesKey("debug_logging_enabled")
        val DEBUG_TOASTS_ENABLED = booleanPreferencesKey("debug_toasts_enabled")
        val BACKUP_INTERVAL = stringPreferencesKey("backup_interval")
        val BACKUP_RETENTION_COUNT = intPreferencesKey("backup_retention_count")
        val LAST_BACKUP_SUCCESS = booleanPreferencesKey("last_backup_success")
        val LAST_BACKUP_TIMESTAMP = longPreferencesKey("last_backup_timestamp")
        val LAST_BACKUP_ERROR = stringPreferencesKey("last_backup_error")
        val LAST_BACKUP_MISSING_APPS = stringSetPreferencesKey("last_backup_missing_apps")
        val APP_BACKUP_CONFIG_JSON = stringPreferencesKey("app_backup_config_json")
        val VOXCONNECT_ENABLED = booleanPreferencesKey("voxconnect_enabled")
        val VOXCONNECT_PORT = intPreferencesKey("voxconnect_port")
        val VOXCONNECT_MEDIA_CONTROL_ENABLED = booleanPreferencesKey("voxconnect_media_control_enabled")
        val VOXCONNECT_MONITORED_APPS_JSON = stringPreferencesKey("voxconnect_monitored_apps_json")
        val IMPORT_MODE = stringPreferencesKey("import_mode")
    }

    override val settingsFlow: Flow<HubSettings> = dataStore.data.map { prefs ->
        HubSettings(
            themeDarkMode = prefs[Keys.THEME_DARK_MODE] ?: HubSettings.THEME_SYSTEM,
            themeColored = prefs[Keys.THEME_COLORED] ?: true,
            debugLoggingEnabled = prefs[Keys.DEBUG_LOGGING_ENABLED] ?: false,
            debugToastsEnabled = prefs[Keys.DEBUG_TOASTS_ENABLED] ?: false,
            backupInterval = prefs[Keys.BACKUP_INTERVAL] ?: HubSettings.INTERVAL_OFF,
            backupRetentionCount = prefs[Keys.BACKUP_RETENTION_COUNT] ?: HubSettings.RETENTION_5,
            lastBackupSuccess = prefs[Keys.LAST_BACKUP_SUCCESS],
            lastBackupTimestamp = prefs[Keys.LAST_BACKUP_TIMESTAMP],
            lastBackupError = prefs[Keys.LAST_BACKUP_ERROR],
            lastBackupMissingApps = (prefs[Keys.LAST_BACKUP_MISSING_APPS] ?: emptySet()).sorted(),
            appBackupConfigs = AppBackupConfig.decodeMap(prefs[Keys.APP_BACKUP_CONFIG_JSON] ?: "{}"),
            voxConnectEnabled = prefs[Keys.VOXCONNECT_ENABLED] ?: false,
            voxConnectPort = prefs[Keys.VOXCONNECT_PORT] ?: HubSettings.VOXCONNECT_DEFAULT_PORT,
            voxConnectMediaControlEnabled = prefs[Keys.VOXCONNECT_MEDIA_CONTROL_ENABLED] ?: false,
            voxConnectMonitoredApps = decodeMonitoredApps(prefs[Keys.VOXCONNECT_MONITORED_APPS_JSON] ?: "{}"),
            importMode = prefs[Keys.IMPORT_MODE] ?: HubSettings.IMPORT_MODE_MERGE
        )
    }

    @Volatile private var cachedSnapshot: HubSettings? = null

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            settingsFlow.collect { cachedSnapshot = it }
        }
    }

    override fun getSnapshot(): HubSettings =
        cachedSnapshot ?: runBlocking { settingsFlow.first() }.also { cachedSnapshot = it }

    override suspend fun setThemeDarkMode(mode: String) {
        dataStore.edit { it[Keys.THEME_DARK_MODE] = mode }
    }

    override suspend fun setThemeColored(colored: Boolean) {
        dataStore.edit { it[Keys.THEME_COLORED] = colored }
    }

    override suspend fun setDebugLoggingEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.DEBUG_LOGGING_ENABLED] = enabled }
    }

    override suspend fun setDebugToastsEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.DEBUG_TOASTS_ENABLED] = enabled }
    }

    override suspend fun setBackupInterval(interval: String) {
        dataStore.edit { it[Keys.BACKUP_INTERVAL] = interval }
    }

    override suspend fun setBackupRetentionCount(count: Int) {
        dataStore.edit { it[Keys.BACKUP_RETENTION_COUNT] = count }
    }

    override suspend fun setImportMode(mode: String) {
        dataStore.edit { it[Keys.IMPORT_MODE] = mode }
    }

    override suspend fun setAppBackupConfig(packageName: String, config: AppBackupConfig) {
        dataStore.edit { prefs ->
            val current = AppBackupConfig.decodeMap(prefs[Keys.APP_BACKUP_CONFIG_JSON] ?: "{}")
            prefs[Keys.APP_BACKUP_CONFIG_JSON] = AppBackupConfig.encodeMap(current + (packageName to config))
        }
    }

    override suspend fun recordBackupResult(
        success: Boolean,
        timestampMillis: Long,
        error: String?,
        missingApps: List<String>
    ) {
        dataStore.edit { prefs ->
            prefs[Keys.LAST_BACKUP_SUCCESS] = success
            prefs[Keys.LAST_BACKUP_TIMESTAMP] = timestampMillis
            if (error == null) prefs.remove(Keys.LAST_BACKUP_ERROR) else prefs[Keys.LAST_BACKUP_ERROR] = error
            if (missingApps.isEmpty()) {
                prefs.remove(Keys.LAST_BACKUP_MISSING_APPS)
            } else {
                prefs[Keys.LAST_BACKUP_MISSING_APPS] = missingApps.toSet()
            }
        }
    }

    override suspend fun setVoxConnectEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.VOXCONNECT_ENABLED] = enabled }
    }

    override suspend fun setVoxConnectPort(port: Int) {
        dataStore.edit { it[Keys.VOXCONNECT_PORT] = port }
    }

    override suspend fun setVoxConnectMediaControlEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.VOXCONNECT_MEDIA_CONTROL_ENABLED] = enabled }
    }

    override suspend fun setVoxConnectMonitoredApp(domain: String, monitored: Boolean) {
        dataStore.edit { prefs ->
            val current = decodeMonitoredApps(prefs[Keys.VOXCONNECT_MONITORED_APPS_JSON] ?: "{}")
            prefs[Keys.VOXCONNECT_MONITORED_APPS_JSON] = encodeMonitoredApps(current + (domain to monitored))
        }
    }

    override suspend fun restoreSettings(settings: HubSettings) {
        dataStore.edit { prefs ->
            prefs[Keys.THEME_DARK_MODE] = settings.themeDarkMode
            prefs[Keys.THEME_COLORED] = settings.themeColored
            prefs[Keys.DEBUG_LOGGING_ENABLED] = settings.debugLoggingEnabled
            prefs[Keys.DEBUG_TOASTS_ENABLED] = settings.debugToastsEnabled
            prefs[Keys.BACKUP_INTERVAL] = settings.backupInterval
            prefs[Keys.BACKUP_RETENTION_COUNT] = settings.backupRetentionCount
            prefs[Keys.APP_BACKUP_CONFIG_JSON] = AppBackupConfig.encodeMap(settings.appBackupConfigs)
            prefs[Keys.VOXCONNECT_MEDIA_CONTROL_ENABLED] = settings.voxConnectMediaControlEnabled
            prefs[Keys.VOXCONNECT_MONITORED_APPS_JSON] = encodeMonitoredApps(settings.voxConnectMonitoredApps)
            prefs[Keys.IMPORT_MODE] = settings.importMode
        }
    }

    private fun decodeMonitoredApps(json: String): Map<String, Boolean> = try {
        val o = JSONObject(json)
        o.keys().asSequence().associateWith { o.optBoolean(it, false) }
    } catch (e: Exception) {
        emptyMap()
    }

    private fun encodeMonitoredApps(map: Map<String, Boolean>): String {
        val o = JSONObject()
        map.forEach { (domain, monitored) -> o.put(domain, monitored) }
        return o.toString()
    }
}
