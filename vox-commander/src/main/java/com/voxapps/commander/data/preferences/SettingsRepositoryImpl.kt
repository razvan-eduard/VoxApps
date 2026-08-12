package com.voxapps.commander.data.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.voxapps.commander.data.remote.RemoteModelRegistry
import com.voxapps.commander.utils.fromJsonOrNull
import com.voxapps.logging.Logger
import com.voxapps.commander.utils.Strings
import com.voxapps.commander.utils.AppScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class SettingsRepositoryImpl(
    context: Context
) : SettingsRepository {

    private val appContext = context.applicationContext
    private val dataStore: DataStore<Preferences> = DataStoreProvider.get(appContext)
    private val gson = Gson()

    // Encrypted storage for API key only
    private val masterKey = MasterKey.Builder(appContext)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs = EncryptedSharedPreferences.create(
        appContext,
        "vox_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /**
     * Names of the entries in the encrypted store — [Keys]' counterpart for the other store.
     *
     * Written out as constants for the same reason the DataStore keys are: they were string
     * literals repeated at each read and each write, which is one typo away from a credential that
     * saves to one name and is read from another. Stored names, so never renamed.
     */
    private object SecureKeys {
        /** One entry per engine that declares `requires_api_key`, addressed by the engine key
         *  itself: `engine_apikey_OPENAI`, `engine_apikey_wake_porcupine`, and so on. A namespace
         *  rather than a fixed set of names, so an engine that needs a credential needs no code. */
        const val ENGINE_PREFIX = "engine_apikey_"

        fun forEngine(engineKey: String) = "$ENGINE_PREFIX$engineKey"
        fun engineOf(prefName: String) = prefName.removePrefix(ENGINE_PREFIX)
        fun isEngineKey(prefName: String) = prefName.startsWith(ENGINE_PREFIX)

        /** The same idea for search providers, which own their keys by provider name. */
        const val SEARCH_PREFIX = "search_apikey_"

        fun forSearchProvider(providerName: String) = "$SEARCH_PREFIX$providerName"
        fun searchProviderOf(prefName: String) = prefName.removePrefix(SEARCH_PREFIX)
        fun isSearchKey(prefName: String) = prefName.startsWith(SEARCH_PREFIX)

        /** The single-key names used before credentials were per engine. Read once by
         *  [migrateLegacyCredentials] and then gone; never written again. */
        const val LEGACY_OPENAI = "api_key"
        const val LEGACY_PICOVOICE = "picovoice_access_key"
    }

    /**
     * Which engine inherits each single-key credential.
     *
     * `api_key` seeds *both* cloud OpenAI engines because that is what it was: one value the intent
     * interpreter and the transcription engine both read. Splitting it without seeding both would
     * silently unconfigure whichever the user thought of second.
     */
    private val legacyCredentialOwners = listOf(
        SecureKeys.LEGACY_OPENAI to listOf(Strings.AiProcessors.OPENAI, Strings.Processors.WHISPER_API),
        // The models.json key for Porcupine. A literal rather than a reference into the service
        // layer, which the data layer has no business importing — and it is a stored identifier.
        SecureKeys.LEGACY_PICOVOICE to listOf("wake_porcupine")
    )

    // --- DATASTORE KEYS ---
    private object Keys {
        // Language
        val LANGUAGE = stringPreferencesKey("language")
        val VOICE_LANGUAGE = stringPreferencesKey("voice_language")
        val VOICE_LANGUAGE_AUTO_DETECT = booleanPreferencesKey("voice_language_auto_detect")
        val MODEL_FILTER_LANG = stringPreferencesKey("model_filter_lang")

        // Voice engine
        val VOICE_PROCESSOR = stringPreferencesKey("voice_processor")
        val ACTIVE_VOICE_MODEL_ID = stringPreferencesKey("active_voice_model_id")
        val ACTIVE_WAKE_MODEL_ID = stringPreferencesKey("active_wake_model_id")

        // Intent engine
        val AI_PROCESSOR = stringPreferencesKey("ai_processor")
        val ACTIVE_INTENT_MODEL_ID = stringPreferencesKey("active_intent_model_id")
        val CLOUD_INTELLIGENCE_ENABLED = booleanPreferencesKey("cloud_intelligence_enabled")

        // Wake word
        val WAKE_WORD = stringPreferencesKey("wake_word")
        val WAKE_WORD_ENABLED = booleanPreferencesKey("wake_word_enabled")
        val WAKE_WORD_MODEL_PATH = stringPreferencesKey("wake_word_model_path")
        val COMMAND_QUEUE_ENABLED = booleanPreferencesKey("command_queue_enabled")
        val WAKE_WORD_PROFILE = stringPreferencesKey("wake_word_profile")
        val WAKE_WORD_ENGINE_TYPE = stringPreferencesKey("wake_word_engine_type")
        /** Legacy: the Picovoice key lived here in plaintext until [migratePicovoiceKey] moved it
         *  to the encrypted store. Kept solely so that migration can find and clear it. */
        val PICOVOICE_ACCESS_KEY = stringPreferencesKey("picovoice_access_key")
        val WAKE_WORD_SENSITIVITY = stringPreferencesKey("wake_word_sensitivity")
        val WAKE_WORD_AEC_ENABLED = booleanPreferencesKey("wake_word_aec_enabled")
        val WAKE_WORD_MUSIC_DUCK_ENABLED = booleanPreferencesKey("wake_word_music_duck_enabled")
        val STT_SENSITIVITY = stringPreferencesKey("stt_sensitivity")

        // Offline fallback
        val OFFLINE_FALLBACK_TIMEOUT = intPreferencesKey("offline_fallback_timeout")
        val DEFAULT_OFFLINE_MODEL = stringPreferencesKey("default_offline_model")
        val DEFAULT_VOICE_FALLBACK_PROCESSOR = stringPreferencesKey("default_voice_fallback_processor")
        val DEFAULT_VOICE_FALLBACK_MODEL = stringPreferencesKey("default_voice_fallback_model")
        val DEFAULT_INTENT_FALLBACK_PROCESSOR = stringPreferencesKey("default_intent_fallback_processor")
        val DEFAULT_INTENT_FALLBACK_MODEL = stringPreferencesKey("default_intent_fallback_model")

        // Logging
        val DEBUG_LOGGING_ENABLED = booleanPreferencesKey("debug_logging_enabled")
        val DEBUG_TOASTS_ENABLED = booleanPreferencesKey("debug_toasts_enabled")
        val THEME_DARK_MODE = stringPreferencesKey("theme_dark_mode")
        val THEME_COLORED = booleanPreferencesKey("theme_colored")

        // Vulkan
        val VULKAN_INCOMPATIBLE = booleanPreferencesKey("vulkan_incompatible")
        val VULKAN_PROBE_DONE = booleanPreferencesKey("vulkan_probe_done")
        val VULKAN_RUNTIME_ATTEMPT = booleanPreferencesKey("vulkan_runtime_attempt")
        val VULKAN_RUNTIME_VERIFIED = booleanPreferencesKey("vulkan_runtime_verified")
        val EXPERIMENTAL_VULKAN_ENABLED = booleanPreferencesKey("experimental_vulkan_enabled")

        // Whisper Engine (DLC)
        val WHISPER_SYSTEM_ENABLED = booleanPreferencesKey("whisper_system_enabled")

        // Remote repository
        val MODEL_REPO_BASE_URL = stringPreferencesKey("model_repo_base_url")
        val USE_REMOTE_SCHEMAS = booleanPreferencesKey("use_remote_schemas")
        val SCHEMA_STORE_MIGRATED = booleanPreferencesKey("schema_store_migrated")
        val IMPORT_SELECTION_MIGRATED = booleanPreferencesKey("import_selection_migrated")

        // Model download state
        val DOWNLOADED_MODEL_IDS = stringSetPreferencesKey("downloaded_model_ids")

        // Custom model paths (stored as JSON map)
        val CUSTOM_MODEL_PATHS_JSON = stringPreferencesKey("custom_model_paths_json")

        // Per-engine model selections (stored as JSON map)
        val ENGINE_MODEL_SELECTIONS_JSON = stringPreferencesKey("engine_model_selections_json")

        // Per-category search provider selections (stored as JSON map)
        val SEARCH_PROVIDER_SELECTIONS_JSON = stringPreferencesKey("search_provider_selections_json")

        // Default apps per domain (stored as JSON map: "audio" -> "com.spotify.music")
        val DEFAULT_APP_PACKAGES_JSON = stringPreferencesKey("default_app_packages_json")

        // Domain -> list of selected packages (stored as JSON map of lists)
        val DOMAIN_APP_PACKAGES_JSON = stringPreferencesKey("domain_app_packages_json")

        // Custom domain names (stored as JSON list)
        val CUSTOM_DOMAINS_JSON = stringPreferencesKey("custom_domains_json")

        // Domain -> filter mode (stored as JSON map: "audio" -> "user")
        val DOMAIN_APP_FILTERS_JSON = stringPreferencesKey("domain_app_filters_json")

        // Cached app list JSON (for fast startup, avoids PackageManager scan)
        val APP_CACHE_JSON = stringPreferencesKey("app_cache_json")

        // Media / External services
        val SPOTIFY_CLIENT_ID = stringPreferencesKey("spotify_client_id")
        val PIPED_API_URL = stringPreferencesKey("piped_api_url")
        val PIPED_REGION = stringPreferencesKey("piped_region")
        val YOUTUBE_URL_ENGINE = stringPreferencesKey("youtube_url_engine")
        val RETURN_AFTER_ACTION_APPS_JSON = stringPreferencesKey("return_after_action_apps_json")
        val EXTERNAL_TRIGGER_ENABLED = booleanPreferencesKey("external_trigger_enabled")

        // Download preference
        val DOWNLOAD_PREFERENCE = stringPreferencesKey("download_preference")

        // TTS
        val TTS_ENABLED = booleanPreferencesKey("tts_enabled")
        val TTS_ENGINE_TYPE = stringPreferencesKey("tts_engine_type")
        val TTS_SPEECH_RATE = floatPreferencesKey("tts_speech_rate")
        val TTS_PITCH = floatPreferencesKey("tts_pitch")
        val TTS_AUDIO_FOCUS_MODE = stringPreferencesKey("tts_audio_focus_mode")
        val OVERLAY_TEXT_SIZE = floatPreferencesKey("overlay_text_size")
        val PIPER_VOICE_MODEL_ID = stringPreferencesKey("piper_voice_model_id")

        // App Aliases
        val APP_ALIAS_RULES_JSON = stringPreferencesKey("app_alias_rules_json")

        // Location: Home Town fallback (stored as strings since DataStore has no doublePreferencesKey),
        // cache TTL, and "always use this location" (shared :core:location module).
        val LOCATION_HOME_TOWN_LAT = stringPreferencesKey("location_home_town_lat")
        val LOCATION_HOME_TOWN_LON = stringPreferencesKey("location_home_town_lon")
        val LOCATION_CACHE_TTL = stringPreferencesKey("location_cache_ttl")
        val LOCATION_ALWAYS_USE_HOME_TOWN = booleanPreferencesKey("location_always_use_home_town")

        // Backup & Restore (local)
        val BACKUP_INCLUDE_SETTINGS = booleanPreferencesKey("backup_include_settings")
        val BACKUP_INCLUDE_DATA = booleanPreferencesKey("backup_include_data")
        val BACKUP_INCLUDE_API_KEYS = booleanPreferencesKey("backup_include_api_keys")
        val BACKUP_IMPORT_MODE = stringPreferencesKey("backup_import_mode")

        // First launch / tutorial
        val FIRST_LAUNCH_COMPLETED = booleanPreferencesKey("first_launch_completed")
        val TUTORIAL_COMPLETED = booleanPreferencesKey("tutorial_completed")
    }

    private val TAG = "SettingsRepository"

    // Migration flag key
    private val KEY_MIGRATION_DONE = booleanPreferencesKey("migration_from_shared_prefs_done")

    /**
     * One-time migration from old EncryptedSharedPreferences to DataStore.
     * Reads all values from the old prefs file and writes them to DataStore.
     * Safe to call on every launch - only runs once.
     */
    suspend fun migrateFromSharedPreferencesIfNeeded() {
        val current = dataStore.data.first()
        if (current[KEY_MIGRATION_DONE] == true) return

        Logger.log("Starting migration from EncryptedSharedPreferences to DataStore", TAG)
        try {
            val oldPrefs = EncryptedSharedPreferences.create(
                appContext,
                Strings.Preferences.PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            val all = oldPrefs.all
            if (all.isEmpty()) {
                Logger.log("Old prefs empty, marking migration done", TAG)
                dataStore.edit { it[KEY_MIGRATION_DONE] = true }
                return
            }

            // Migrate API key to new secure prefs
            oldPrefs.getString(Strings.Preferences.KEY_API_KEY, null)?.let { key ->
                encryptedPrefs.edit().putString(SecureKeys.LEGACY_OPENAI, key).apply()
            }

            dataStore.edit { prefs ->
                // Language
                all[Strings.Preferences.KEY_LANGUAGE]?.let { prefs[Keys.LANGUAGE] = it as String }
                all[Strings.Preferences.KEY_VOICE_LANGUAGE]?.let { prefs[Keys.VOICE_LANGUAGE] = it as String }

                // Voice engine
                all[Strings.Preferences.KEY_VOICE_PROCESSOR]?.let { prefs[Keys.VOICE_PROCESSOR] = it as String }
                all[Strings.Preferences.KEY_ACTIVE_VOICE_MODEL_ID]?.let { prefs[Keys.ACTIVE_VOICE_MODEL_ID] = it as String }

                // Intent engine
                all[Strings.Preferences.KEY_AI_PROCESSOR]?.let { prefs[Keys.AI_PROCESSOR] = it as String }
                all[Strings.Preferences.KEY_ACTIVE_INTENT_MODEL_ID]?.let { prefs[Keys.ACTIVE_INTENT_MODEL_ID] = it as String }
                all[Strings.Preferences.KEY_CLOUD_INTELLIGENCE_ENABLED]?.let { prefs[Keys.CLOUD_INTELLIGENCE_ENABLED] = it as Boolean }

                // Wake word
                all[Strings.Preferences.KEY_WAKE_WORD]?.let { prefs[Keys.WAKE_WORD] = it as String }
                all[Strings.Preferences.KEY_WAKE_WORD_ENABLED]?.let { prefs[Keys.WAKE_WORD_ENABLED] = it as Boolean }
                all[Strings.Preferences.KEY_WAKE_WORD_MODEL_PATH]?.let { prefs[Keys.WAKE_WORD_MODEL_PATH] = it as String }

                // Offline fallback
                all[Strings.Preferences.KEY_OFFLINE_FALLBACK_TIMEOUT]?.let { prefs[Keys.OFFLINE_FALLBACK_TIMEOUT] = it as Int }
                all["default_offline_model"]?.let { prefs[Keys.DEFAULT_OFFLINE_MODEL] = it as String }
                all["default_voice_fallback_processor"]?.let { prefs[Keys.DEFAULT_VOICE_FALLBACK_PROCESSOR] = it as String }
                all["default_voice_fallback_model"]?.let { prefs[Keys.DEFAULT_VOICE_FALLBACK_MODEL] = it as String }
                all["default_intent_fallback_processor"]?.let { prefs[Keys.DEFAULT_INTENT_FALLBACK_PROCESSOR] = it as String }
                all["default_intent_fallback_model"]?.let { prefs[Keys.DEFAULT_INTENT_FALLBACK_MODEL] = it as String }

                // Logging
                all["debug_logging_enabled"]?.let { prefs[Keys.DEBUG_LOGGING_ENABLED] = it as Boolean }
                all["debug_toasts_enabled"]?.let { prefs[Keys.DEBUG_TOASTS_ENABLED] = it as Boolean }

                // Vulkan
                all[Strings.Preferences.KEY_VULKAN_INCOMPATIBLE]?.let { prefs[Keys.VULKAN_INCOMPATIBLE] = it as Boolean }
                all[Strings.Preferences.KEY_VULKAN_PROBE_DONE]?.let { prefs[Keys.VULKAN_PROBE_DONE] = it as Boolean }
                all[Strings.Preferences.KEY_VULKAN_RUNTIME_ATTEMPT]?.let { prefs[Keys.VULKAN_RUNTIME_ATTEMPT] = it as Boolean }
                all[Strings.Preferences.KEY_VULKAN_RUNTIME_VERIFIED]?.let { prefs[Keys.VULKAN_RUNTIME_VERIFIED] = it as Boolean }
                all["experimental_vulkan_enabled"]?.let { prefs[Keys.EXPERIMENTAL_VULKAN_ENABLED] = it as Boolean }

                // Whisper Engine (DLC)
                all["whisper_system_enabled"]?.let { prefs[Keys.WHISPER_SYSTEM_ENABLED] = it as Boolean }

                // Gemini

                // Remote repository
                all[Strings.Preferences.KEY_MODEL_REPO_BASE_URL]?.let { prefs[Keys.MODEL_REPO_BASE_URL] = it as String }

                // Model downloaded flags -> collect into set
                val downloadedIds = all.keys
                    .filter { it.startsWith(Strings.Preferences.KEY_MODEL_DOWNLOADED_PREFIX) }
                    .filter { all[it] as? Boolean == true }
                    .map { it.removePrefix(Strings.Preferences.KEY_MODEL_DOWNLOADED_PREFIX) }
                    .toSet()
                if (downloadedIds.isNotEmpty()) {
                    prefs[Keys.DOWNLOADED_MODEL_IDS] = downloadedIds
                }

                // Custom model paths -> collect into JSON map
                val customPaths = mutableMapOf<String, String>()
                all.keys.filter { it.startsWith("custom_model_path_") }.forEach { key ->
                    val path = all[key] as? String
                    if (!path.isNullOrBlank()) {
                        val mapKey = key.removePrefix("custom_model_path_")
                        customPaths[mapKey] = path
                    }
                }
                if (customPaths.isNotEmpty()) {
                    prefs[Keys.CUSTOM_MODEL_PATHS_JSON] = gson.toJson(customPaths)
                }

                prefs[KEY_MIGRATION_DONE] = true
            }

            // Clear old prefs after successful migration
            oldPrefs.edit().clear().apply()
            Logger.log("Migration complete, old prefs cleared", TAG)
        } catch (e: Exception) {
            Logger.log("Migration failed: ${e.message}", TAG)
            // Mark as done anyway to avoid retrying on every launch
            dataStore.edit { it[KEY_MIGRATION_DONE] = true }
        }
    }

    // --- REACTIVE FLOW ---
    override val settingsFlow: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            // apiKey is deliberately absent: it lives in the encrypted store, which
            // this flow cannot observe, so reading them here produced a copy that went stale the
            // moment a key was entered and stayed stale until an unrelated setting happened to
            // write. They are served by credentialsFlow, and the fields on AppSettings survive only
            // as backup transport (see CommanderExportHandler).

            language = prefs[Keys.LANGUAGE] ?: Strings.Preferences.DEFAULT_LANGUAGE,
            voiceLanguage = prefs[Keys.VOICE_LANGUAGE] ?: Strings.Preferences.DEFAULT_LANGUAGE,
            voiceLanguageAutoDetect = prefs[Keys.VOICE_LANGUAGE_AUTO_DETECT] ?: false,
            modelFilterLang = prefs[Keys.MODEL_FILTER_LANG] ?: Strings.Preferences.DEFAULT_LANGUAGE,

            voiceProcessor = prefs[Keys.VOICE_PROCESSOR] ?: com.voxapps.commander.data.remote.RemoteModelRegistry.getDefaultVoiceEngineKey() ?: "",
            activeVoiceModelId = prefs[Keys.ACTIVE_VOICE_MODEL_ID],
            // Falls back to the legacy key so an existing install keeps the model it was using.
            activeWakeModelId = prefs[Keys.ACTIVE_WAKE_MODEL_ID] ?: prefs[Keys.WAKE_WORD_MODEL_PATH],

            aiProcessor = prefs[Keys.AI_PROCESSOR] ?: com.voxapps.commander.data.remote.RemoteModelRegistry.getDefaultLlmEngineKey() ?: "",
            activeIntentModelId = prefs[Keys.ACTIVE_INTENT_MODEL_ID],
            cloudIntelligenceEnabled = prefs[Keys.CLOUD_INTELLIGENCE_ENABLED] ?: false,

            engineModelSelections = parseStringMap(prefs[Keys.ENGINE_MODEL_SELECTIONS_JSON]),
            searchProviderSelections = parseStringMap(prefs[Keys.SEARCH_PROVIDER_SELECTIONS_JSON]),

            wakeWord = prefs[Keys.WAKE_WORD] ?: "hi vosk",
            wakeWordEnabled = prefs[Keys.WAKE_WORD_ENABLED] ?: false,
            wakeWordModelPath = prefs[Keys.WAKE_WORD_MODEL_PATH],
            commandQueueEnabled = prefs[Keys.COMMAND_QUEUE_ENABLED] ?: true,
            wakeWordProfileJson = prefs[Keys.WAKE_WORD_PROFILE],
            wakeWordEngineType = normalizeEngineKey(prefs[Keys.WAKE_WORD_ENGINE_TYPE] ?: RemoteModelRegistry.getDefaultWakeWordEngineKey()),
            wakeWordSensitivity = prefs[Keys.WAKE_WORD_SENSITIVITY] ?: "medium",
            wakeWordAecEnabled = prefs[Keys.WAKE_WORD_AEC_ENABLED] ?: false,
            wakeWordMusicDuckEnabled = prefs[Keys.WAKE_WORD_MUSIC_DUCK_ENABLED] ?: true,
            sttSensitivity = prefs[Keys.STT_SENSITIVITY] ?: "medium",

            offlineFallbackTimeout = prefs[Keys.OFFLINE_FALLBACK_TIMEOUT] ?: 10,
            defaultOfflineModel = prefs[Keys.DEFAULT_OFFLINE_MODEL] ?: "tiny",
            defaultVoiceFallbackProcessor = prefs[Keys.DEFAULT_VOICE_FALLBACK_PROCESSOR],
            defaultVoiceFallbackModel = prefs[Keys.DEFAULT_VOICE_FALLBACK_MODEL],
            defaultIntentFallbackProcessor = prefs[Keys.DEFAULT_INTENT_FALLBACK_PROCESSOR],
            defaultIntentFallbackModel = prefs[Keys.DEFAULT_INTENT_FALLBACK_MODEL],

            debugLoggingEnabled = prefs[Keys.DEBUG_LOGGING_ENABLED] ?: true,
            debugToastsEnabled = prefs[Keys.DEBUG_TOASTS_ENABLED] ?: false,
            themeDarkMode = prefs[Keys.THEME_DARK_MODE] ?: "SYSTEM",
            themeColored = prefs[Keys.THEME_COLORED] ?: false,

            vulkanIncompatible = prefs[Keys.VULKAN_INCOMPATIBLE] ?: false,
            vulkanProbeDone = prefs[Keys.VULKAN_PROBE_DONE] ?: false,
            vulkanRuntimeAttempt = prefs[Keys.VULKAN_RUNTIME_ATTEMPT] ?: false,
            vulkanRuntimeVerified = prefs[Keys.VULKAN_RUNTIME_VERIFIED] ?: false,
            experimentalVulkanEnabled = prefs[Keys.EXPERIMENTAL_VULKAN_ENABLED] ?: false,

            isWhisperSystemEnabled = prefs[Keys.WHISPER_SYSTEM_ENABLED] ?: false,


            modelRepoBaseUrl = prefs[Keys.MODEL_REPO_BASE_URL] ?: Strings.Preferences.DEFAULT_MODEL_REPO_URL,
            useRemoteSchemas = prefs[Keys.USE_REMOTE_SCHEMAS] ?: true,
            schemaStoreMigrated = prefs[Keys.SCHEMA_STORE_MIGRATED] ?: false,
            importSelectionMigrated = prefs[Keys.IMPORT_SELECTION_MIGRATED] ?: false,

            downloadedModelIds = prefs[Keys.DOWNLOADED_MODEL_IDS] ?: emptySet(),
            customModelPaths = parseCustomModelPaths(prefs[Keys.CUSTOM_MODEL_PATHS_JSON]),

            defaultAppPackages = parseStringMap(prefs[Keys.DEFAULT_APP_PACKAGES_JSON]),

            domainAppPackages = parseStringListMap(prefs[Keys.DOMAIN_APP_PACKAGES_JSON]),

            customDomains = parseStringList(prefs[Keys.CUSTOM_DOMAINS_JSON]),

            domainAppFilters = parseStringMap(prefs[Keys.DOMAIN_APP_FILTERS_JSON]),

            appCacheJson = prefs[Keys.APP_CACHE_JSON],

            spotifyClientId = prefs[Keys.SPOTIFY_CLIENT_ID],
            pipedApiUrl = prefs[Keys.PIPED_API_URL],
            pipedRegion = prefs[Keys.PIPED_REGION],
            youtubeUrlEngine = prefs[Keys.YOUTUBE_URL_ENGINE] ?: "piped",
            returnAfterActionApps = parseStringList(prefs[Keys.RETURN_AFTER_ACTION_APPS_JSON]),
            externalTriggerEnabled = prefs[Keys.EXTERNAL_TRIGGER_ENABLED] ?: true,

            downloadPreference = prefs[Keys.DOWNLOAD_PREFERENCE] ?: "wifi_and_metered",

            ttsEnabled = prefs[Keys.TTS_ENABLED] ?: true,
            ttsEngineType = normalizeEngineKey(prefs[Keys.TTS_ENGINE_TYPE] ?: "android"),
            ttsSpeechRate = prefs[Keys.TTS_SPEECH_RATE] ?: 1.0f,
            ttsPitch = prefs[Keys.TTS_PITCH] ?: 1.0f,
            ttsAudioFocusMode = prefs[Keys.TTS_AUDIO_FOCUS_MODE] ?: "duck",
            overlayTextSize = prefs[Keys.OVERLAY_TEXT_SIZE] ?: 1.0f,
            piperVoiceModelId = prefs[Keys.PIPER_VOICE_MODEL_ID],
            appAliasRules = parseAppAliasRules(prefs[Keys.APP_ALIAS_RULES_JSON]),
            locationHomeTownLat = prefs[Keys.LOCATION_HOME_TOWN_LAT]?.toDoubleOrNull(),
            locationHomeTownLon = prefs[Keys.LOCATION_HOME_TOWN_LON]?.toDoubleOrNull(),
            locationCacheTtl = prefs[Keys.LOCATION_CACHE_TTL] ?: "ONE_DAY",
            locationAlwaysUseHomeTown = prefs[Keys.LOCATION_ALWAYS_USE_HOME_TOWN] ?: false,
            backupIncludeSettings = prefs[Keys.BACKUP_INCLUDE_SETTINGS] ?: true,
            backupIncludeData = prefs[Keys.BACKUP_INCLUDE_DATA] ?: true,
            backupIncludeApiKeys = prefs[Keys.BACKUP_INCLUDE_API_KEYS] ?: false,
            backupImportMode = prefs[Keys.BACKUP_IMPORT_MODE] ?: "merge",
            firstLaunchCompleted = prefs[Keys.FIRST_LAUNCH_COMPLETED] ?: false,
            tutorialCompleted = prefs[Keys.TUTORIAL_COMPLETED] ?: false
        )
    }

    // --- SYNCHRONOUS READS ---
    // Cache kept warm by collecting settingsFlow on a background scope, so the hot-path
    // getters below (called from MainActivity/WakeWordService onCreate, IntentRouter.route)
    // never block the calling thread. Only the very first read before the cache warms up
    // falls back to a one-off runBlocking.
    @Volatile private var cachedSnapshot: AppSettings? = null

    init {
        AppScope.io.launch {
            // Ordered: the Picovoice key first reaches the encrypted store under its old single-key
            // name, and only then can be namespaced with the rest.
            migratePicovoiceKey()
            migrateLegacyCredentials()
        }
        AppScope.io.launch {
            settingsFlow.collect { cachedSnapshot = it }
        }
    }

    override suspend fun restoreImportedSettings(imported: AppSettings) {
        // Secrets only round-trip if the export actually carried them (includeSecrets was on) —
        // absent/default means "not part of this import", not "clear it".
        //
        // The per-engine map is applied first and the single-key fields fill only what it left
        // unset, so a backup carrying both (every backup this build writes) restores from the map,
        // while one written before per-engine credentials existed still restores from the old
        // fields. An engine named by neither keeps whatever is already on the device.
        val restored = mutableSetOf<String>()
        for ((engineKey, key) in imported.engineApiKeys) {
            setEngineApiKey(engineKey, key)
            restored += engineKey
        }
        legacyCredentialOwners.forEach { (legacyName, engines) ->
            val value = when (legacyName) {
                SecureKeys.LEGACY_OPENAI -> imported.apiKey
                else -> imported.picovoiceAccessKey
            } ?: return@forEach
            engines.filterNot { it in restored }.forEach { setEngineApiKey(it, value) }
        }

        for ((provider, key) in imported.searchProviderApiKeys) {
            setSearchProviderApiKey(provider, key)
        }

        dataStore.edit { prefs ->
            prefs[Keys.LANGUAGE] = imported.language
            prefs[Keys.VOICE_LANGUAGE] = imported.voiceLanguage
            prefs[Keys.VOICE_LANGUAGE_AUTO_DETECT] = imported.voiceLanguageAutoDetect
            prefs[Keys.MODEL_FILTER_LANG] = imported.modelFilterLang

            prefs[Keys.VOICE_PROCESSOR] = normalizeEngineKey(imported.voiceProcessor)
            imported.activeVoiceModelId?.let { prefs[Keys.ACTIVE_VOICE_MODEL_ID] = it }
                ?: prefs.remove(Keys.ACTIVE_VOICE_MODEL_ID)
            imported.activeWakeModelId?.let { prefs[Keys.ACTIVE_WAKE_MODEL_ID] = it }
                ?: prefs.remove(Keys.ACTIVE_WAKE_MODEL_ID)

            prefs[Keys.AI_PROCESSOR] = normalizeEngineKey(imported.aiProcessor)
            imported.activeIntentModelId?.let { prefs[Keys.ACTIVE_INTENT_MODEL_ID] = it }
                ?: prefs.remove(Keys.ACTIVE_INTENT_MODEL_ID)
            prefs[Keys.CLOUD_INTELLIGENCE_ENABLED] = imported.cloudIntelligenceEnabled

            prefs[Keys.ENGINE_MODEL_SELECTIONS_JSON] = gson.toJson(imported.engineModelSelections)
            prefs[Keys.SEARCH_PROVIDER_SELECTIONS_JSON] = gson.toJson(imported.searchProviderSelections)

            prefs[Keys.WAKE_WORD] = imported.wakeWord
            prefs[Keys.WAKE_WORD_ENABLED] = imported.wakeWordEnabled
            prefs[Keys.COMMAND_QUEUE_ENABLED] = imported.commandQueueEnabled
            prefs[Keys.WAKE_WORD_ENGINE_TYPE] = normalizeEngineKey(imported.wakeWordEngineType)
            prefs[Keys.WAKE_WORD_SENSITIVITY] = imported.wakeWordSensitivity
            prefs[Keys.WAKE_WORD_AEC_ENABLED] = imported.wakeWordAecEnabled
            prefs[Keys.WAKE_WORD_MUSIC_DUCK_ENABLED] = imported.wakeWordMusicDuckEnabled
            prefs[Keys.STT_SENSITIVITY] = imported.sttSensitivity

            prefs[Keys.OFFLINE_FALLBACK_TIMEOUT] = imported.offlineFallbackTimeout
            prefs[Keys.DEFAULT_OFFLINE_MODEL] = imported.defaultOfflineModel
            imported.defaultVoiceFallbackProcessor?.let { prefs[Keys.DEFAULT_VOICE_FALLBACK_PROCESSOR] = it }
                ?: prefs.remove(Keys.DEFAULT_VOICE_FALLBACK_PROCESSOR)
            imported.defaultVoiceFallbackModel?.let { prefs[Keys.DEFAULT_VOICE_FALLBACK_MODEL] = it }
                ?: prefs.remove(Keys.DEFAULT_VOICE_FALLBACK_MODEL)
            imported.defaultIntentFallbackProcessor?.let { prefs[Keys.DEFAULT_INTENT_FALLBACK_PROCESSOR] = it }
                ?: prefs.remove(Keys.DEFAULT_INTENT_FALLBACK_PROCESSOR)
            imported.defaultIntentFallbackModel?.let { prefs[Keys.DEFAULT_INTENT_FALLBACK_MODEL] = it }
                ?: prefs.remove(Keys.DEFAULT_INTENT_FALLBACK_MODEL)

            prefs[Keys.DEBUG_LOGGING_ENABLED] = imported.debugLoggingEnabled
            prefs[Keys.DEBUG_TOASTS_ENABLED] = imported.debugToastsEnabled
            prefs[Keys.THEME_DARK_MODE] = imported.themeDarkMode
            prefs[Keys.THEME_COLORED] = imported.themeColored

            prefs[Keys.EXPERIMENTAL_VULKAN_ENABLED] = imported.experimentalVulkanEnabled

            prefs[Keys.WHISPER_SYSTEM_ENABLED] = imported.isWhisperSystemEnabled

            prefs[Keys.MODEL_REPO_BASE_URL] = imported.modelRepoBaseUrl

            prefs[Keys.DEFAULT_APP_PACKAGES_JSON] = gson.toJson(imported.defaultAppPackages)
            prefs[Keys.DOMAIN_APP_PACKAGES_JSON] = gson.toJson(imported.domainAppPackages)
            prefs[Keys.CUSTOM_DOMAINS_JSON] = gson.toJson(imported.customDomains)
            prefs[Keys.DOMAIN_APP_FILTERS_JSON] = gson.toJson(imported.domainAppFilters)

            imported.spotifyClientId?.let { prefs[Keys.SPOTIFY_CLIENT_ID] = it } ?: prefs.remove(Keys.SPOTIFY_CLIENT_ID)
            imported.pipedApiUrl?.let { prefs[Keys.PIPED_API_URL] = it } ?: prefs.remove(Keys.PIPED_API_URL)
            imported.pipedRegion?.let { prefs[Keys.PIPED_REGION] = it } ?: prefs.remove(Keys.PIPED_REGION)
            prefs[Keys.YOUTUBE_URL_ENGINE] = imported.youtubeUrlEngine
            prefs[Keys.RETURN_AFTER_ACTION_APPS_JSON] = gson.toJson(imported.returnAfterActionApps)
            prefs[Keys.EXTERNAL_TRIGGER_ENABLED] = imported.externalTriggerEnabled

            prefs[Keys.DOWNLOAD_PREFERENCE] = imported.downloadPreference

            prefs[Keys.TTS_ENABLED] = imported.ttsEnabled
            prefs[Keys.TTS_ENGINE_TYPE] = normalizeEngineKey(imported.ttsEngineType)
            prefs[Keys.TTS_SPEECH_RATE] = imported.ttsSpeechRate
            prefs[Keys.TTS_PITCH] = imported.ttsPitch
            prefs[Keys.TTS_AUDIO_FOCUS_MODE] = imported.ttsAudioFocusMode
            prefs[Keys.OVERLAY_TEXT_SIZE] = imported.overlayTextSize
            imported.piperVoiceModelId?.let { prefs[Keys.PIPER_VOICE_MODEL_ID] = it } ?: prefs.remove(Keys.PIPER_VOICE_MODEL_ID)

            prefs[Keys.APP_ALIAS_RULES_JSON] = gson.toJson(imported.appAliasRules)

            imported.locationHomeTownLat?.let { prefs[Keys.LOCATION_HOME_TOWN_LAT] = it.toString() }
                ?: prefs.remove(Keys.LOCATION_HOME_TOWN_LAT)
            imported.locationHomeTownLon?.let { prefs[Keys.LOCATION_HOME_TOWN_LON] = it.toString() }
                ?: prefs.remove(Keys.LOCATION_HOME_TOWN_LON)
            prefs[Keys.LOCATION_CACHE_TTL] = imported.locationCacheTtl
            prefs[Keys.LOCATION_ALWAYS_USE_HOME_TOWN] = imported.locationAlwaysUseHomeTown
            prefs[Keys.BACKUP_INCLUDE_SETTINGS] = imported.backupIncludeSettings
            prefs[Keys.BACKUP_INCLUDE_DATA] = imported.backupIncludeData
            prefs[Keys.BACKUP_INCLUDE_API_KEYS] = imported.backupIncludeApiKeys
            prefs[Keys.BACKUP_IMPORT_MODE] = imported.backupImportMode

            prefs[Keys.FIRST_LAUNCH_COMPLETED] = imported.firstLaunchCompleted
            prefs[Keys.TUTORIAL_COMPLETED] = imported.tutorialCompleted
        }
    }

    override fun getSettingsSnapshot(): AppSettings {
        // A cached snapshot whose aiProcessor is the empty-string fallback was mapped before the
        // model registry had loaded (the default engine key is derived from the registry at map
        // time, and nothing re-maps the flow until a DataStore write). Serving it would silently
        // drop the cascade's L2 stage — the resolver answers null for "" — so such a snapshot is
        // re-read once the registry can actually answer.
        val cached = cachedSnapshot
        if (cached != null &&
            !(cached.aiProcessor.isEmpty() &&
                com.voxapps.commander.data.remote.RemoteModelRegistry.getDefaultLlmEngineKey() != null)
        ) {
            return cached
        }
        return runBlocking { settingsFlow.first() }.also { cachedSnapshot = it }
    }

    /**
     * Read straight from the store rather than from a cache.
     *
     * A credential is small, read rarely (once per cloud call, not per frame) and must never be
     * stale — a key entered a second ago has to be the one the next request uses. That is the
     * opposite trade-off from [getSettingsSnapshot], which caches because it is read on hot paths.
     */
    override fun getCredentialsSnapshot(): Credentials = readCredentials()

    private fun readCredentials(): Credentials {
        val all = encryptedPrefs.all
        val byEngine = all
            .filterKeys { SecureKeys.isEngineKey(it) }
            .mapNotNull { (name, value) -> (value as? String)?.let { SecureKeys.engineOf(name) to it } }
            .toMap()
        val bySearchProvider = all
            .filterKeys { SecureKeys.isSearchKey(it) }
            .mapNotNull { (name, value) -> (value as? String)?.let { SecureKeys.searchProviderOf(name) to it } }
            .toMap()
        return Credentials(byEngine, bySearchProvider)
    }

    /**
     * Emits on every credential write, using the store's own change callback.
     *
     * `EncryptedSharedPreferences` is a `SharedPreferences`, so it can say when it changed — which
     * is what makes this a real observation rather than a periodic guess. Nothing has to be nudged
     * from the DataStore side, and nothing derived from a credential can be left showing a value
     * that is no longer stored.
     */
    override val credentialsFlow: Flow<Credentials> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || SecureKeys.isEngineKey(key) || SecureKeys.isSearchKey(key)) {
                trySend(readCredentials())
            }
        }
        encryptedPrefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(readCredentials())
        awaitClose { encryptedPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()
    override fun getSpotifyClientIdSync(): String? = getSettingsSnapshot().spotifyClientId
    override fun getPipedApiUrlSync(): String? = getSettingsSnapshot().pipedApiUrl
    override fun getPipedRegionSync(): String? = getSettingsSnapshot().pipedRegion
    override fun getYoutubeUrlEngineSync(): String = getSettingsSnapshot().youtubeUrlEngine
    override fun getReturnAfterActionAppsSync(): List<String> = getSettingsSnapshot().returnAfterActionApps
    override fun getExternalTriggerEnabledSync(): Boolean = getSettingsSnapshot().externalTriggerEnabled

    // --- SYNCHRONOUS WRITE (crash cookie) ---
    override fun setVulkanRuntimeAttemptSync(active: Boolean) {
        // Must be synchronous: if process crashes during GPU work, this flag must already be on disk.
        // Use runBlocking to ensure DataStore writes before returning.
        runBlocking {
            dataStore.edit { prefs ->
                prefs[Keys.VULKAN_RUNTIME_ATTEMPT] = active
            }
        }
    }

    // --- API / CLOUD ---
    override suspend fun setEngineApiKey(engineKey: String, key: String?) {
        val name = SecureKeys.forEngine(engineKey)
        encryptedPrefs.edit().apply {
            if (!key.isNullOrBlank()) putString(name, key) else remove(name)
        }.apply()
    }

    /**
     * Moves the single-key credentials into the per-engine namespace.
     *
     * Self-guarding in the same way as [migratePicovoiceKey]: the legacy entry is removed as part of
     * the move, so the read finds nothing on every later launch. An engine that already has a
     * credential keeps it — a migration must never overwrite a value the user has since set.
     */
    private fun migrateLegacyCredentials() {
        val editor = encryptedPrefs.edit()
        var moved = false
        legacyCredentialOwners.forEach { (legacyName, engines) ->
            val value = encryptedPrefs.getString(legacyName, null) ?: return@forEach
            if (value.isNotBlank()) {
                engines.filter { encryptedPrefs.getString(SecureKeys.forEngine(it), null) == null }
                    .forEach { editor.putString(SecureKeys.forEngine(it), value); moved = true }
            }
            editor.remove(legacyName)
        }
        editor.apply()
        if (moved) Logger.log("Moved single-key credentials into the per-engine namespace", TAG)
    }

    /**
     * Removes every stored trace of the retired Google LLM engines (Gemini Cloud / Gemini Nano).
     *
     * Self-guarding like [migratePicovoiceKey] rather than flag-driven: each step keys off the
     * retired value still being present, so later launches find nothing to do. The processor
     * remap must run before the first settings read that routes a command — a selection naming a
     * retired engine resolves to no engine at all, which would silently drop the cascade's
     * primary stage — which is why [com.voxapps.commander.di.AppContainer] calls this on the same
     * blocking path as [migrateFromSharedPreferencesIfNeeded].
     */
    suspend fun migrateGoogleLlmRemoval() {
        // Stored identifiers of engines that no longer exist — literals by the same reasoning as
        // the legacy names in [legacyCredentialOwners].
        val retired = setOf("GEMINI_CLOUD", "GEMINI_NATIVE")
        val legacyIncompatible = booleanPreferencesKey("gemini_incompatible")
        val legacyGeminiCredential = "gemini_api_key"
        dataStore.edit { prefs ->
            if (prefs[Keys.AI_PROCESSOR] in retired) {
                val fallback = com.voxapps.commander.data.remote.RemoteModelRegistry.getDefaultLlmEngineKey()
                if (fallback != null) prefs[Keys.AI_PROCESSOR] = fallback
                else prefs.remove(Keys.AI_PROCESSOR)
                Logger.log("aiProcessor named a retired engine — reset to the default", TAG)
            }
            if (prefs[Keys.DEFAULT_INTENT_FALLBACK_PROCESSOR] in retired) {
                prefs.remove(Keys.DEFAULT_INTENT_FALLBACK_PROCESSOR)
                prefs.remove(Keys.DEFAULT_INTENT_FALLBACK_MODEL)
            }
            prefs.remove(legacyIncompatible)
        }
        encryptedPrefs.edit()
            .remove(SecureKeys.forEngine("GEMINI_CLOUD"))
            .remove(legacyGeminiCredential)
            .apply()
    }

    /**
     * Removes every stored trace of the retired LiteRT-LM engine key and its model formats.
     *
     * Self-guarding like [migrateGoogleLlmRemoval]: each step keys off a retired value or file
     * still being present. The key remap goes to `nlu_llm` rather than the app default — a user
     * who had `nlu_llm_litertlm` selected chose the *local* engine, and `nlu_llm` is that same
     * choice under the collapsed key. Orphaned `.task`/`.litertlm` model files are deleted and
     * their ids dropped from the downloaded set: nothing can load them, and they are
     * gigabyte-class.
     */
    suspend fun migrateLiteRtRemoval() {
        val retiredKey = "nlu_llm_litertlm" // stored identifier of an engine that no longer exists
        dataStore.edit { prefs ->
            if (prefs[Keys.AI_PROCESSOR] == retiredKey) prefs[Keys.AI_PROCESSOR] = "nlu_llm"
            if (prefs[Keys.DEFAULT_INTENT_FALLBACK_PROCESSOR] == retiredKey) {
                prefs[Keys.DEFAULT_INTENT_FALLBACK_PROCESSOR] = "nlu_llm"
                prefs.remove(Keys.DEFAULT_INTENT_FALLBACK_MODEL)
            }

            val orphans = appContext.getExternalFilesDir(null)?.listFiles()
                ?.filter { it.isFile && (it.name.endsWith(".task") || it.name.endsWith(".litertlm")) }
                .orEmpty()
            if (orphans.isNotEmpty()) {
                val orphanIds = orphans.map { it.name.substringBeforeLast('.') }.toSet()
                orphans.forEach { it.delete() }
                prefs[Keys.DOWNLOADED_MODEL_IDS] =
                    (prefs[Keys.DOWNLOADED_MODEL_IDS] ?: emptySet()) - orphanIds
                if (prefs[Keys.ACTIVE_INTENT_MODEL_ID] in orphanIds) {
                    prefs.remove(Keys.ACTIVE_INTENT_MODEL_ID)
                }
                Logger.log("Deleted ${orphans.size} retired-format model file(s)", TAG)
            }
        }
    }

    /**
     * Moves an existing Porcupine key out of DataStore, where it was kept in plaintext.
     *
     * The backup has always classified it as a secret and stripped it from an export without
     * "include API keys" — so the app already considered it one while storing it beside the
     * ordinary preferences. Self-guarding rather than flag-driven: the legacy entry is removed as
     * part of the move, so the read below finds nothing on every later launch. An existing
     * encrypted value always wins, so a re-run can never overwrite a newer key with an older one.
     */
    private suspend fun migratePicovoiceKey() {
        val legacy = dataStore.data.first()[Keys.PICOVOICE_ACCESS_KEY] ?: return
        if (legacy.isNotBlank() && encryptedPrefs.getString(SecureKeys.LEGACY_PICOVOICE, null) == null) {
            encryptedPrefs.edit().putString(SecureKeys.LEGACY_PICOVOICE, legacy).apply()
            Logger.log("Moved the Picovoice access key into encrypted storage", TAG)
        }
        dataStore.edit { it.remove(Keys.PICOVOICE_ACCESS_KEY) }
    }

    // --- LANGUAGE ---
    override suspend fun setLanguage(lang: String) {
        dataStore.edit { it[Keys.LANGUAGE] = lang }
    }

    override suspend fun setVoiceLanguage(lang: String) {
        dataStore.edit { it[Keys.VOICE_LANGUAGE] = lang }
    }

    override suspend fun setVoiceLanguageAutoDetect(enabled: Boolean) {
        dataStore.edit { it[Keys.VOICE_LANGUAGE_AUTO_DETECT] = enabled }
    }

    override suspend fun setModelFilterLang(lang: String) {
        dataStore.edit { it[Keys.MODEL_FILTER_LANG] = lang }
    }

    // --- VOICE ENGINE ---
    override suspend fun setVoiceProcessor(processor: String) {
        dataStore.edit { it[Keys.VOICE_PROCESSOR] = processor }
    }

    override suspend fun setActiveWakeModelId(modelId: String?) {
        dataStore.edit { prefs ->
            if (modelId == null) prefs.remove(Keys.ACTIVE_WAKE_MODEL_ID) else prefs[Keys.ACTIVE_WAKE_MODEL_ID] = modelId
        }
    }

    override suspend fun setActiveVoiceModelId(modelId: String?) {
        dataStore.edit { prefs ->
            if (modelId != null) prefs[Keys.ACTIVE_VOICE_MODEL_ID] = modelId
            else prefs.remove(Keys.ACTIVE_VOICE_MODEL_ID)
        }
    }

    override suspend fun setEngineModelSelection(engineKey: String, modelId: String) {
        dataStore.edit { prefs ->
            val currentMap = parseStringMap(prefs[Keys.ENGINE_MODEL_SELECTIONS_JSON]).toMutableMap()
            currentMap[engineKey] = modelId
            prefs[Keys.ENGINE_MODEL_SELECTIONS_JSON] = gson.toJson(currentMap)
        }
    }

    // --- INTENT ENGINE ---
    override suspend fun setAiProcessor(processor: String) {
        dataStore.edit { it[Keys.AI_PROCESSOR] = processor }
    }

    override suspend fun setActiveIntentModelId(modelId: String?) {
        dataStore.edit { prefs ->
            if (modelId != null) prefs[Keys.ACTIVE_INTENT_MODEL_ID] = modelId
            else prefs.remove(Keys.ACTIVE_INTENT_MODEL_ID)
        }
    }

    override suspend fun setCloudIntelligenceEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.CLOUD_INTELLIGENCE_ENABLED] = enabled }
    }

    // --- WAKE WORD ---
    override suspend fun setWakeWord(word: String) {
        dataStore.edit { it[Keys.WAKE_WORD] = word }
    }

    override suspend fun setWakeWordEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.WAKE_WORD_ENABLED] = enabled }
    }

    override suspend fun setCommandQueueEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.COMMAND_QUEUE_ENABLED] = enabled }
    }

    override suspend fun setWakeWordProfile(profileJson: String?) {
        dataStore.edit { prefs ->
            if (profileJson != null) prefs[Keys.WAKE_WORD_PROFILE] = profileJson
            else prefs.remove(Keys.WAKE_WORD_PROFILE)
        }
    }

    override fun getWakeWordProfileJson(): String? {
        return runBlocking { dataStore.data.first()[Keys.WAKE_WORD_PROFILE] }
    }

    override suspend fun setWakeWordModelPath(path: String?) {
        dataStore.edit { prefs ->
            if (path != null) prefs[Keys.WAKE_WORD_MODEL_PATH] = path
            else prefs.remove(Keys.WAKE_WORD_MODEL_PATH)
        }
    }

    override suspend fun setWakeWordEngineType(engineType: String) {
        dataStore.edit { it[Keys.WAKE_WORD_ENGINE_TYPE] = engineType }
    }

    override suspend fun setWakeWordSensitivity(sensitivity: String) {
        dataStore.edit { it[Keys.WAKE_WORD_SENSITIVITY] = sensitivity }
    }

    override suspend fun setWakeWordAecEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.WAKE_WORD_AEC_ENABLED] = enabled }
    }

    override suspend fun setWakeWordMusicDuckEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.WAKE_WORD_MUSIC_DUCK_ENABLED] = enabled }
    }

    override suspend fun setSttSensitivity(sensitivity: String) {
        dataStore.edit { it[Keys.STT_SENSITIVITY] = sensitivity }
    }

    // --- OFFLINE FALLBACK ---
    override suspend fun setOfflineFallbackTimeout(seconds: Int) {
        dataStore.edit { it[Keys.OFFLINE_FALLBACK_TIMEOUT] = seconds }
    }

    override suspend fun setDefaultOfflineModel(modelId: String) {
        dataStore.edit { it[Keys.DEFAULT_OFFLINE_MODEL] = modelId }
    }

    override suspend fun clearDefaultOfflineModel() {
        dataStore.edit { it.remove(Keys.DEFAULT_OFFLINE_MODEL) }
    }

    override suspend fun setDefaultVoiceFallback(processor: String, modelId: String) {
        dataStore.edit {
            it[Keys.DEFAULT_VOICE_FALLBACK_PROCESSOR] = processor
            it[Keys.DEFAULT_VOICE_FALLBACK_MODEL] = modelId
        }
    }

    override suspend fun clearDefaultVoiceFallback() {
        dataStore.edit {
            it.remove(Keys.DEFAULT_VOICE_FALLBACK_PROCESSOR)
            it.remove(Keys.DEFAULT_VOICE_FALLBACK_MODEL)
        }
    }

    override suspend fun setDefaultIntentFallback(processor: String, modelId: String) {
        dataStore.edit {
            it[Keys.DEFAULT_INTENT_FALLBACK_PROCESSOR] = processor
            it[Keys.DEFAULT_INTENT_FALLBACK_MODEL] = modelId
        }
    }

    override suspend fun clearDefaultIntentFallback() {
        dataStore.edit {
            it.remove(Keys.DEFAULT_INTENT_FALLBACK_PROCESSOR)
            it.remove(Keys.DEFAULT_INTENT_FALLBACK_MODEL)
        }
    }

    override suspend fun clearDefaultOfflineFallback() {
        clearDefaultVoiceFallback()
        clearDefaultIntentFallback()
    }

    // --- LOGGING ---
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

    // --- VULKAN ---
    override suspend fun setVulkanIncompatible(incompatible: Boolean) {
        dataStore.edit { it[Keys.VULKAN_INCOMPATIBLE] = incompatible }
    }

    override suspend fun setVulkanProbeDone(done: Boolean) {
        dataStore.edit { it[Keys.VULKAN_PROBE_DONE] = done }
    }

    override suspend fun setVulkanRuntimeVerified(verified: Boolean) {
        dataStore.edit { it[Keys.VULKAN_RUNTIME_VERIFIED] = verified }
    }

    override suspend fun setExperimentalVulkanEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.EXPERIMENTAL_VULKAN_ENABLED] = enabled }
    }

    // --- WHISPER ENGINE (DLC) ---
    override suspend fun setWhisperSystemEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.WHISPER_SYSTEM_ENABLED] = enabled }
    }

    // --- REMOTE REPOSITORY ---
    override suspend fun clearAllSettings() {
        dataStore.edit { it.clear() }
        Logger.log("Settings cleared — every setting is back to its default", TAG)
    }

    override suspend fun setImportSelectionMigrated(done: Boolean) {
        dataStore.edit { it[Keys.IMPORT_SELECTION_MIGRATED] = done }
    }

    override suspend fun setSchemaStoreMigrated(done: Boolean) {
        dataStore.edit { it[Keys.SCHEMA_STORE_MIGRATED] = done }
    }

    override suspend fun setUseRemoteSchemas(enabled: Boolean) {
        dataStore.edit { it[Keys.USE_REMOTE_SCHEMAS] = enabled }
    }

    override suspend fun setModelRepoBaseUrl(url: String) {
        dataStore.edit { it[Keys.MODEL_REPO_BASE_URL] = url }
    }

    // --- MODEL DOWNLOAD STATE ---
    override suspend fun setModelDownloaded(modelId: String, isDownloaded: Boolean) {
        dataStore.edit { prefs ->
            val current = prefs[Keys.DOWNLOADED_MODEL_IDS] ?: emptySet()
            val updated = if (isDownloaded) current + modelId else current - modelId
            prefs[Keys.DOWNLOADED_MODEL_IDS] = updated
        }
    }

    // --- CUSTOM MODEL PATHS ---
    override suspend fun setCustomModelPath(engineKey: String, path: String, langCode: String?) {
        dataStore.edit { prefs ->
            val mapKey = if (langCode != null) "${engineKey}_$langCode" else engineKey
            val currentJson = prefs[Keys.CUSTOM_MODEL_PATHS_JSON] ?: "{}"
            val currentMap = parseCustomModelPaths(currentJson).toMutableMap()
            if (path.isBlank()) {
                currentMap.remove(mapKey)
            } else {
                currentMap[mapKey] = path
            }
            prefs[Keys.CUSTOM_MODEL_PATHS_JSON] = gson.toJson(currentMap)
        }
    }

    // --- DEFAULT APPS PER DOMAIN ---
    override suspend fun setDefaultAppPackage(domain: String, packageName: String?) {
        dataStore.edit { prefs ->
            val currentMap = parseStringMap(prefs[Keys.DEFAULT_APP_PACKAGES_JSON]).toMutableMap()
            if (packageName != null) {
                currentMap[domain] = packageName
            } else {
                currentMap.remove(domain)
            }
            prefs[Keys.DEFAULT_APP_PACKAGES_JSON] = gson.toJson(currentMap)
        }
    }

    override suspend fun setDomainApps(domain: String, packages: List<String>) {
        dataStore.edit { prefs ->
            val currentMap = parseStringListMap(prefs[Keys.DOMAIN_APP_PACKAGES_JSON]).toMutableMap()
            if (packages.isEmpty()) {
                currentMap.remove(domain)
            } else {
                currentMap[domain] = packages
            }
            prefs[Keys.DOMAIN_APP_PACKAGES_JSON] = gson.toJson(currentMap)
        }
    }

    override suspend fun setDomainAppFilter(domain: String, filter: String) {
        dataStore.edit { prefs ->
            val currentMap = parseStringMap(prefs[Keys.DOMAIN_APP_FILTERS_JSON]).toMutableMap()
            currentMap[domain] = filter
            prefs[Keys.DOMAIN_APP_FILTERS_JSON] = gson.toJson(currentMap)
        }
    }

    override suspend fun setAppCache(json: String) {
        dataStore.edit { prefs ->
            prefs[Keys.APP_CACHE_JSON] = json
        }
    }

    override suspend fun clearAppCache() {
        dataStore.edit { prefs ->
            prefs.remove(Keys.APP_CACHE_JSON)
        }
    }

    override suspend fun addCustomDomain(name: String) {
        dataStore.edit { prefs ->
            val currentList = parseStringList(prefs[Keys.CUSTOM_DOMAINS_JSON]).toMutableList()
            if (name !in currentList) {
                currentList.add(name)
                prefs[Keys.CUSTOM_DOMAINS_JSON] = gson.toJson(currentList)
            }
        }
    }

    override suspend fun removeCustomDomain(name: String) {
        dataStore.edit { prefs ->
            val currentList = parseStringList(prefs[Keys.CUSTOM_DOMAINS_JSON]).toMutableList()
            currentList.remove(name)
            prefs[Keys.CUSTOM_DOMAINS_JSON] = gson.toJson(currentList)
            // Also clean up app selections for this domain
            val appMap = parseStringListMap(prefs[Keys.DOMAIN_APP_PACKAGES_JSON]).toMutableMap()
            appMap.remove(name)
            prefs[Keys.DOMAIN_APP_PACKAGES_JSON] = gson.toJson(appMap)
            val defaultMap = parseStringMap(prefs[Keys.DEFAULT_APP_PACKAGES_JSON]).toMutableMap()
            defaultMap.remove(name)
            prefs[Keys.DEFAULT_APP_PACKAGES_JSON] = gson.toJson(defaultMap)
        }
    }

    // --- MEDIA / EXTERNAL SERVICES ---
    override suspend fun setSpotifyClientId(clientId: String?) {
        dataStore.edit { prefs ->
            if (clientId != null) prefs[Keys.SPOTIFY_CLIENT_ID] = clientId
            else prefs.remove(Keys.SPOTIFY_CLIENT_ID)
        }
    }

    override suspend fun setPipedApiUrl(url: String?) {
        dataStore.edit { prefs ->
            if (url != null) prefs[Keys.PIPED_API_URL] = url
            else prefs.remove(Keys.PIPED_API_URL)
        }
    }

    override suspend fun setPipedRegion(region: String?) {
        dataStore.edit { prefs ->
            if (region != null) prefs[Keys.PIPED_REGION] = region
            else prefs.remove(Keys.PIPED_REGION)
        }
    }

    override suspend fun setYoutubeUrlEngine(engine: String) {
        dataStore.edit { prefs ->
            prefs[Keys.YOUTUBE_URL_ENGINE] = engine
        }
    }

    override suspend fun setReturnAfterActionApps(apps: List<String>) {
        dataStore.edit { prefs ->
            if (apps.isEmpty()) prefs.remove(Keys.RETURN_AFTER_ACTION_APPS_JSON)
            else prefs[Keys.RETURN_AFTER_ACTION_APPS_JSON] = gson.toJson(apps)
        }
    }

    override suspend fun setExternalTriggerEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.EXTERNAL_TRIGGER_ENABLED] = enabled }
    }

    // --- DOWNLOAD PREFERENCE ---
    override suspend fun setDownloadPreference(preference: String) {
        dataStore.edit { it[Keys.DOWNLOAD_PREFERENCE] = preference }
    }

    override suspend fun setSearchProviderSelection(category: String, providerName: String) {
        dataStore.edit { prefs ->
            val currentMap = parseStringMap(prefs[Keys.SEARCH_PROVIDER_SELECTIONS_JSON]).toMutableMap()
            currentMap[category] = providerName
            prefs[Keys.SEARCH_PROVIDER_SELECTIONS_JSON] = gson.toJson(currentMap)
        }
    }

    // --- SEARCH PROVIDER API KEYS (stored in encrypted prefs) ---
    override fun getSearchProviderApiKeySync(providerName: String): String? =
        encryptedPrefs.getString(SecureKeys.forSearchProvider(providerName), null)

    override suspend fun setSearchProviderApiKey(providerName: String, key: String?) {
        val name = SecureKeys.forSearchProvider(providerName)
        encryptedPrefs.edit().apply {
            if (key != null) putString(name, key) else remove(name)
        }.apply()
    }

    override fun getAllSearchProviderApiKeys(): Map<String, String> =
        readCredentials().bySearchProvider

    // --- DECLARATIVE API INTEGRATION OAUTH TOKENS (stored in encrypted prefs, keyed by service id).
    // Key format "${serviceId}_access_token" etc matches the pre-existing "spotify_access_token"
    // naming exactly, so Spotify's already-persisted tokens keep working with zero migration. ---
    override fun getServiceAccessTokenSync(serviceId: String): String? = encryptedPrefs.getString("${serviceId}_access_token", null)
    override fun getServiceRefreshTokenSync(serviceId: String): String? = encryptedPrefs.getString("${serviceId}_refresh_token", null)
    override fun getServiceTokenExpirySync(serviceId: String): Long = encryptedPrefs.getLong("${serviceId}_token_expiry", 0)

    override suspend fun setServiceTokens(serviceId: String, accessToken: String?, refreshToken: String?, expiry: Long) {
        encryptedPrefs.edit().apply {
            if (accessToken != null) putString("${serviceId}_access_token", accessToken) else remove("${serviceId}_access_token")
            if (refreshToken != null) putString("${serviceId}_refresh_token", refreshToken) else remove("${serviceId}_refresh_token")
            putLong("${serviceId}_token_expiry", expiry)
        }.apply()
    }

    // --- DECLARATIVE API INTEGRATION DEVICE ID (stored in encrypted prefs, keyed by service id) ---
    override fun getServiceClientIdSync(serviceId: String): String? =
        encryptedPrefs.getString("${'$'}{serviceId}_client_id", null)
            // Spotify's client id predates per-service storage; read the old key until it is moved.
            ?: if (serviceId == "spotify") getSettingsSnapshot().spotifyClientId else null

    override suspend fun setServiceClientId(serviceId: String, clientId: String?) {
        encryptedPrefs.edit().apply {
            if (!clientId.isNullOrBlank()) putString("${'$'}{serviceId}_client_id", clientId)
            else remove("${'$'}{serviceId}_client_id")
        }.apply()
    }

    override fun getServiceDeviceIdSync(serviceId: String): String? = encryptedPrefs.getString("${serviceId}_device_id", null)

    override suspend fun setServiceDeviceId(serviceId: String, deviceId: String?) {
        encryptedPrefs.edit().apply {
            if (deviceId != null) putString("${serviceId}_device_id", deviceId) else remove("${serviceId}_device_id")
        }.apply()
    }

    // --- TTS ---
    override suspend fun setTtsEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.TTS_ENABLED] = enabled }
    }

    override suspend fun setTtsEngineType(engineType: String) {
        dataStore.edit { it[Keys.TTS_ENGINE_TYPE] = engineType }
    }

    override suspend fun setTtsSpeechRate(rate: Float) {
        dataStore.edit { it[Keys.TTS_SPEECH_RATE] = rate }
    }

    override suspend fun setTtsPitch(pitch: Float) {
        dataStore.edit { it[Keys.TTS_PITCH] = pitch }
    }

    override suspend fun setTtsAudioFocusMode(mode: String) {
        dataStore.edit { it[Keys.TTS_AUDIO_FOCUS_MODE] = mode }
    }

    override suspend fun setPiperVoiceModelId(id: String?) {
        dataStore.edit {
            if (id != null) it[Keys.PIPER_VOICE_MODEL_ID] = id else it.remove(Keys.PIPER_VOICE_MODEL_ID)
        }
    }

    override suspend fun setOverlayTextSize(size: Float) {
        dataStore.edit { it[Keys.OVERLAY_TEXT_SIZE] = size }
    }

    // --- HELPERS ---
    private fun parseCustomModelPaths(json: String?): Map<String, String> = parseStringMap(json)

    /**
     * Maps legacy engine spellings onto the `models.json` keys the app uses today.
     *
     * One table for every domain, applied where settings are read. The TTS alias used to live in a
     * `TtsEngineType` enum resolved at the point of use instead, which meant two mechanisms for the
     * same problem and an unnormalised value persisting in DataStore indefinitely.
     *
     * Nothing is rewritten on disk: stored identifiers stay exactly as they are, because
     * `restoreImportedSettings` writes them verbatim from backups with no version field and there is
     * no migration mechanism. Normalising on read costs nothing and keeps old backups importable.
     */
    /**
     * The current key for an engine spelling that predates the `models.json` keys.
     *
     * Applied on every read *and* on import. Import used to write these values raw, which is why
     * the wake-word service carried its own `"wake_porcupine" || "porcupine"` tests: a restored
     * backup could put the short name back into storage. Normalising at both doors is what lets the
     * readers stop guarding, and this table stays the one place the old spellings are known.
     */
    private fun normalizeEngineKey(raw: String): String = when (raw) {
        "vosk" -> "wake_vosk"
        "porcupine" -> "wake_porcupine"
        "openwakeword" -> "wake_openwakeword"
        "piper" -> "piper_tts"
        // Retired engines (stored identifiers, so literals): a selection naming one would resolve
        // to no engine at all and silently drop the cascade's primary stage — fall back to the
        // default local LLM engine instead. The LiteRT key maps to nlu_llm specifically: it was
        // the same local engine under its pre-collapse key.
        "GEMINI_CLOUD", "GEMINI_NATIVE" ->
            com.voxapps.commander.data.remote.RemoteModelRegistry.getDefaultLlmEngineKey() ?: raw
        "nlu_llm_litertlm" -> "nlu_llm"
        else -> raw
    }

    private fun parseStringMap(json: String?): Map<String, String> =
        gson.fromJsonOrNull<Map<String, String>>(json) {
            Logger.log("Failed to parse string map: ${it.message}", TAG)
        } ?: emptyMap()

    private fun parseStringListMap(json: String?): Map<String, List<String>> =
        gson.fromJsonOrNull<Map<String, List<String>>>(json) {
            Logger.log("Failed to parse string list map: ${it.message}", TAG)
        } ?: emptyMap()

    private fun parseStringList(json: String?): List<String> =
        gson.fromJsonOrNull<List<String>>(json) {
            Logger.log("Failed to parse string list: ${it.message}", TAG)
        } ?: emptyList()

    private fun parseAppAliasRules(json: String?): List<AppAliasRule> =
        gson.fromJsonOrNull<List<AppAliasRule>>(json) {
            Logger.log("Failed to parse app alias rules: ${it.message}", TAG)
        } ?: emptyList()

    override suspend fun setAppAliasRules(rules: List<AppAliasRule>) {
        dataStore.edit { prefs ->
            prefs[Keys.APP_ALIAS_RULES_JSON] = gson.toJson(rules)
        }
    }

    // --- LOCATION (Home Town / cache TTL / always-use) ---
    override fun getLocationHomeTownLatSync(): Double? = runBlocking { dataStore.data.first()[Keys.LOCATION_HOME_TOWN_LAT]?.toDoubleOrNull() }
    override fun getLocationHomeTownLonSync(): Double? = runBlocking { dataStore.data.first()[Keys.LOCATION_HOME_TOWN_LON]?.toDoubleOrNull() }

    override suspend fun setLocationHomeTown(lat: Double?, lon: Double?) {
        dataStore.edit { prefs ->
            if (lat != null) prefs[Keys.LOCATION_HOME_TOWN_LAT] = lat.toString()
            else prefs.remove(Keys.LOCATION_HOME_TOWN_LAT)
            if (lon != null) prefs[Keys.LOCATION_HOME_TOWN_LON] = lon.toString()
            else prefs.remove(Keys.LOCATION_HOME_TOWN_LON)
        }
    }

    override fun getLocationCacheTtlSync(): String = runBlocking { dataStore.data.first()[Keys.LOCATION_CACHE_TTL] ?: "ONE_DAY" }
    override suspend fun setLocationCacheTtl(ttl: String) {
        dataStore.edit { it[Keys.LOCATION_CACHE_TTL] = ttl }
    }

    override fun getLocationAlwaysUseHomeTownSync(): Boolean = runBlocking { dataStore.data.first()[Keys.LOCATION_ALWAYS_USE_HOME_TOWN] ?: false }
    override suspend fun setLocationAlwaysUseHomeTown(enabled: Boolean) {
        dataStore.edit { it[Keys.LOCATION_ALWAYS_USE_HOME_TOWN] = enabled }
    }

    // --- BACKUP & RESTORE (local) ---
    override fun getBackupIncludeSettingsSync(): Boolean = runBlocking { dataStore.data.first()[Keys.BACKUP_INCLUDE_SETTINGS] ?: true }
    override suspend fun setBackupIncludeSettings(enabled: Boolean) {
        dataStore.edit { it[Keys.BACKUP_INCLUDE_SETTINGS] = enabled }
    }

    override fun getBackupIncludeDataSync(): Boolean = runBlocking { dataStore.data.first()[Keys.BACKUP_INCLUDE_DATA] ?: true }
    override suspend fun setBackupIncludeData(enabled: Boolean) {
        dataStore.edit { it[Keys.BACKUP_INCLUDE_DATA] = enabled }
    }

    override fun getBackupIncludeApiKeysSync(): Boolean = runBlocking { dataStore.data.first()[Keys.BACKUP_INCLUDE_API_KEYS] ?: false }
    override suspend fun setBackupIncludeApiKeys(enabled: Boolean) {
        dataStore.edit { it[Keys.BACKUP_INCLUDE_API_KEYS] = enabled }
    }

    override fun getBackupImportModeSync(): String = runBlocking { dataStore.data.first()[Keys.BACKUP_IMPORT_MODE] ?: "merge" }
    override suspend fun setBackupImportMode(mode: String) {
        dataStore.edit { it[Keys.BACKUP_IMPORT_MODE] = mode }
    }

    // --- FIRST LAUNCH / TUTORIAL ---
    override fun getFirstLaunchCompletedSync(): Boolean = runBlocking { dataStore.data.first()[Keys.FIRST_LAUNCH_COMPLETED] ?: false }
    override suspend fun setFirstLaunchCompleted(completed: Boolean) {
        dataStore.edit { it[Keys.FIRST_LAUNCH_COMPLETED] = completed }
    }

    override fun getTutorialCompletedSync(): Boolean = runBlocking { dataStore.data.first()[Keys.TUTORIAL_COMPLETED] ?: false }
    override suspend fun setTutorialCompleted(completed: Boolean) {
        dataStore.edit { it[Keys.TUTORIAL_COMPLETED] = completed }
    }
}
