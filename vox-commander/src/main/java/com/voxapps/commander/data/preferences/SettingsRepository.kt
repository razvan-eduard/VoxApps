package com.voxapps.commander.data.preferences

import kotlinx.coroutines.flow.Flow

/**
 * Single source of truth for all persisted settings.
 * Wraps DataStore and exposes reactive Flows + suspend writers.
 * AppStateManager observes [settingsFlow] and combines with runtime state.
 *
 * Flow: UI write → AppStateManager → SettingsRepository → DataStore → Flow → AppStateManager → UI
 */
interface SettingsRepository {

    /**
     * Reactive snapshot of all persisted settings.
     * Emits a new [AppSettings] whenever any value changes.
     */
    val settingsFlow: Flow<AppSettings>

    /**
     * Bulk-applies every field of [imported] EXCEPT raw local paths (wakeWordModelPath/
     * customModelPaths) and pure caches/probe results (appCacheJson/
     * downloadedModelIds/the vulkan flags/geminiIncompatible/wakeWordProfileJson) — those are always
     * left alone since they were never present in an exported snapshot to begin with. Secrets
     * (apiKey/geminiApiKey/picovoiceAccessKey/searchProviderApiKeys) are applied only where
     * [imported] actually carries a value (non-null / non-empty) — an export made without
     * `includeSecrets` leaves them at their [AppSettings] defaults, so the current on-device value is
     * preserved rather than cleared; an export made with it overwrites them. Used by Vox Hub's import
     * flow ([com.voxapps.commander.receiver.VoxCommandReceiver]'s `OP_IMPORT`).
     */
    suspend fun restoreImportedSettings(imported: AppSettings)

    // --- SYNCHRONOUS READS (for non-coroutine consumers during migration) ---
    fun getSettingsSnapshot(): AppSettings

    /**
     * The encrypted store, managed like the DataStore one: [credentialsFlow] to observe,
     * [getCredentialsSnapshot] to read, [setEngineApiKey] to write.
     *
     * This replaces `getApiKeySync()`/`getGeminiApiKeySync()` *and* the copies that used to ride
     * along inside [AppSettings]. Two routes to one value is one route too many: they disagreed,
     * and which caller got the stale one was decided by which line of code it happened to call.
     */
    val credentialsFlow: Flow<Credentials>
    fun getCredentialsSnapshot(): Credentials

    // --- SYNCHRONOUS WRITE (crash cookie: must survive process death immediately) ---
    fun setVulkanRuntimeAttemptSync(active: Boolean)

    /**
     * Stores (or clears, when [key] is null or blank) the credential for [engineKey].
     *
     * One per engine, addressed by the same key the schema uses. There is no per-service setter to
     * add when an engine is added: an engine declaring `requires_api_key` is already asking for a
     * slot, and this provides it.
     */
    suspend fun setEngineApiKey(engineKey: String, key: String?)

    // --- LANGUAGE ---
    suspend fun setLanguage(lang: String)
    suspend fun setVoiceLanguage(lang: String)
    suspend fun setVoiceLanguageAutoDetect(enabled: Boolean)
    suspend fun setModelFilterLang(lang: String)

    // --- VOICE ENGINE ---
    suspend fun setVoiceProcessor(processor: String)
    suspend fun setActiveVoiceModelId(modelId: String?)
    suspend fun setActiveWakeModelId(modelId: String?)

    // --- INTENT ENGINE ---
    suspend fun setAiProcessor(processor: String)
    suspend fun setActiveIntentModelId(modelId: String?)
    suspend fun setCloudIntelligenceEnabled(enabled: Boolean)

    // --- PER-ENGINE MODEL SELECTIONS ---
    suspend fun setEngineModelSelection(engineKey: String, modelId: String)

    // --- WAKE WORD ---
    suspend fun setWakeWord(word: String)
    suspend fun setWakeWordEnabled(enabled: Boolean)
    suspend fun setWakeWordModelPath(path: String?)
    suspend fun setCommandQueueEnabled(enabled: Boolean)
    suspend fun setWakeWordProfile(profileJson: String?)
    fun getWakeWordProfileJson(): String?
    suspend fun setWakeWordEngineType(engineType: String)
    suspend fun setWakeWordSensitivity(sensitivity: String)
    suspend fun setWakeWordAecEnabled(enabled: Boolean)
    suspend fun setWakeWordMusicDuckEnabled(enabled: Boolean)
    suspend fun setSttSensitivity(sensitivity: String)

    // --- OFFLINE FALLBACK ---
    suspend fun setOfflineFallbackTimeout(seconds: Int)
    suspend fun setDefaultOfflineModel(modelId: String)
    suspend fun clearDefaultOfflineModel()
    suspend fun setDefaultVoiceFallback(processor: String, modelId: String)
    suspend fun clearDefaultVoiceFallback()
    suspend fun setDefaultIntentFallback(processor: String, modelId: String)
    suspend fun clearDefaultIntentFallback()
    suspend fun clearDefaultOfflineFallback()

    // --- LOGGING ---
    suspend fun setDebugLoggingEnabled(enabled: Boolean)
    suspend fun setDebugToastsEnabled(enabled: Boolean)
    suspend fun setThemeDarkMode(mode: String)
    suspend fun setThemeColored(colored: Boolean)

    // --- VULKAN ---
    suspend fun setVulkanIncompatible(incompatible: Boolean)
    suspend fun setVulkanProbeDone(done: Boolean)
    suspend fun setVulkanRuntimeVerified(verified: Boolean)
    suspend fun setExperimentalVulkanEnabled(enabled: Boolean)

    // --- WHISPER ENGINE (DLC) ---
    suspend fun setWhisperSystemEnabled(enabled: Boolean)

    // --- GEMINI ---
    suspend fun setGeminiIncompatible(incompatible: Boolean)

    // --- REMOTE REPOSITORY ---
    suspend fun setModelRepoBaseUrl(url: String)
    suspend fun setSchemaAutoUpdate(enabled: Boolean)
    suspend fun setSchemaStoreMigrated(done: Boolean)

    /**
     * Empties the settings store, leaving every setting at its default.
     *
     * Deliberately narrow: the DataStore only. Credentials live in the encrypted store and models on
     * disk, and someone resetting *settings* is not asking to re-enter their API keys or re-download
     * two gigabytes — a reset that takes those with it is one nobody dares press.
     */
    suspend fun clearAllSettings()

    // --- MODEL DOWNLOAD STATE ---
    suspend fun setModelDownloaded(modelId: String, isDownloaded: Boolean)

    // --- CUSTOM MODEL PATHS ---
    suspend fun setCustomModelPath(engineKey: String, path: String, langCode: String? = null)

    // --- DEFAULT APPS PER DOMAIN ---
    suspend fun setDefaultAppPackage(domain: String, packageName: String?)
    suspend fun setDomainApps(domain: String, packages: List<String>)
    suspend fun setDomainAppFilter(domain: String, filter: String)
    suspend fun setAppCache(json: String)
    suspend fun clearAppCache()
    suspend fun addCustomDomain(name: String)
    suspend fun removeCustomDomain(name: String)

    // --- MEDIA / EXTERNAL SERVICES ---
    fun getSpotifyClientIdSync(): String?
    fun getPipedApiUrlSync(): String?
    fun getPipedRegionSync(): String?
    fun getYoutubeUrlEngineSync(): String
    suspend fun setSpotifyClientId(clientId: String?)
    suspend fun setPipedApiUrl(url: String?)
    suspend fun setPipedRegion(region: String?)
    suspend fun setYoutubeUrlEngine(engine: String)
    fun getReturnAfterActionAppsSync(): List<String>
    suspend fun setReturnAfterActionApps(apps: List<String>)
    fun getExternalTriggerEnabledSync(): Boolean
    suspend fun setExternalTriggerEnabled(enabled: Boolean)

    // --- SEARCH PROVIDER API KEYS ---
    /** Per-category provider choice — the same shape as the per-engine model selection above. */
    suspend fun setSearchProviderSelection(category: String, providerName: String)

    fun getSearchProviderApiKeySync(providerName: String): String?
    suspend fun setSearchProviderApiKey(providerName: String, key: String?)
    fun getAllSearchProviderApiKeys(): Map<String, String>

    // --- DOWNLOAD PREFERENCE ---
    suspend fun setDownloadPreference(preference: String)

    // --- DECLARATIVE API INTEGRATION OAUTH TOKENS (keyed by service id, e.g. "spotify") ---
    fun getServiceAccessTokenSync(serviceId: String): String?
    fun getServiceRefreshTokenSync(serviceId: String): String?
    fun getServiceTokenExpirySync(serviceId: String): Long
    suspend fun setServiceTokens(serviceId: String, accessToken: String?, refreshToken: String?, expiry: Long)

    /**
     * The client id a service's OAuth flow needs, keyed by service id.
     *
     * Spotify's lived in its own DataStore key and its own getter, which is why the integrations
     * screen could only ever configure Spotify. Declared integrations each get a slot, and Spotify's
     * existing value is migrated into it.
     */
    fun getServiceClientIdSync(serviceId: String): String?
    suspend fun setServiceClientId(serviceId: String, clientId: String?)

    // --- DECLARATIVE API INTEGRATION DEVICE ID (keyed by service id) ---
    fun getServiceDeviceIdSync(serviceId: String): String?
    suspend fun setServiceDeviceId(serviceId: String, deviceId: String?)

    // --- TTS ---
    suspend fun setTtsEnabled(enabled: Boolean)
    suspend fun setTtsEngineType(engineType: String)
    suspend fun setTtsSpeechRate(rate: Float)
    suspend fun setTtsPitch(pitch: Float)
    suspend fun setTtsAudioFocusMode(mode: String)
    suspend fun setOverlayTextSize(size: Float)
    suspend fun setPiperVoiceModelId(id: String?)

    // --- APP ALIASES ---
    suspend fun setAppAliasRules(rules: List<AppAliasRule>)

    // --- LOCATION (Home Town / cache TTL / always-use, shared :core:location module) ---
    fun getLocationHomeTownLatSync(): Double?
    fun getLocationHomeTownLonSync(): Double?
    suspend fun setLocationHomeTown(lat: Double?, lon: Double?)
    fun getLocationCacheTtlSync(): String
    suspend fun setLocationCacheTtl(ttl: String)
    fun getLocationAlwaysUseHomeTownSync(): Boolean
    suspend fun setLocationAlwaysUseHomeTown(enabled: Boolean)

    // --- BACKUP & RESTORE (local) ---
    fun getBackupIncludeSettingsSync(): Boolean
    suspend fun setBackupIncludeSettings(enabled: Boolean)
    fun getBackupIncludeDataSync(): Boolean
    suspend fun setBackupIncludeData(enabled: Boolean)
    fun getBackupIncludeApiKeysSync(): Boolean
    suspend fun setBackupIncludeApiKeys(enabled: Boolean)
    fun getBackupImportModeSync(): String
    suspend fun setBackupImportMode(mode: String)

    // --- FIRST LAUNCH / TUTORIAL ---
    fun getFirstLaunchCompletedSync(): Boolean
    suspend fun setFirstLaunchCompleted(completed: Boolean)
    fun getTutorialCompletedSync(): Boolean
    suspend fun setTutorialCompleted(completed: Boolean)
}
