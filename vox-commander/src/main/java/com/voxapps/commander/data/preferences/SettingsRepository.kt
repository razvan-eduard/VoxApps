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

    // --- SYNCHRONOUS READS (for non-coroutine consumers during migration) ---
    fun getSettingsSnapshot(): AppSettings
    fun getApiKeySync(): String?
    fun getGeminiApiKeySync(): String?

    // --- SYNCHRONOUS WRITE (crash cookie: must survive process death immediately) ---
    fun setVulkanRuntimeAttemptSync(active: Boolean)

    // --- API / CLOUD ---
    suspend fun setApiKey(key: String?)
    suspend fun setGeminiApiKey(key: String?)

    // --- LANGUAGE ---
    suspend fun setLanguage(lang: String)
    suspend fun setVoiceLanguage(lang: String)
    suspend fun setVoiceLanguageAutoDetect(enabled: Boolean)
    suspend fun setModelFilterLang(lang: String)

    // --- VOICE ENGINE ---
    suspend fun setVoiceProcessor(processor: String)
    suspend fun setActiveVoiceModelId(modelId: String?)

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
    fun getPicovoiceAccessKeySync(): String?
    suspend fun setPicovoiceAccessKey(key: String?)
    suspend fun setWakeWordSensitivity(sensitivity: String)
    suspend fun setWakeWordAecEnabled(enabled: Boolean)
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
    suspend fun setLogLevel(level: String)
    suspend fun setVerboseLoggingEnabled(enabled: Boolean)

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
    suspend fun saveModelsJsonCache(json: String)
    suspend fun clearModelsJsonCache()

    // --- MODEL DOWNLOAD STATE ---
    suspend fun setModelDownloaded(modelId: String, isDownloaded: Boolean)
    suspend fun clearUnusedModelFlags(protectedIds: Set<String>)

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
    fun getSearchProviderApiKeySync(providerName: String): String?
    suspend fun setSearchProviderApiKey(providerName: String, key: String?)
    fun getAllSearchProviderApiKeys(): Map<String, String>

    // --- DOWNLOAD PREFERENCE ---
    suspend fun setDownloadPreference(preference: String)

    // --- SPOTIFY PKCE TOKENS ---
    fun getSpotifyAccessTokenSync(): String?
    fun getSpotifyRefreshTokenSync(): String?
    fun getSpotifyTokenExpirySync(): Long
    suspend fun setSpotifyTokens(accessToken: String?, refreshToken: String?, expiry: Long)

    // --- SPOTIFY DEVICE ID ---
    fun getSpotifyDeviceIdSync(): String?
    suspend fun setSpotifyDeviceId(deviceId: String?)

    // --- TTS ---
    suspend fun setTtsEnabled(enabled: Boolean)
    suspend fun setTtsEngineType(engineType: String)
    suspend fun setTtsSpeechRate(rate: Float)
    suspend fun setTtsPitch(pitch: Float)
    suspend fun setTtsAudioFocusMode(mode: String)
    suspend fun setOverlayTextSize(size: Float)

    // --- APP ALIASES ---
    suspend fun setAppAliasRules(rules: List<AppAliasRule>)

    // --- MANUAL LOCATION ---
    fun getManualLocationLatSync(): Double?
    fun getManualLocationLonSync(): Double?
    suspend fun setManualLocation(lat: Double?, lon: Double?)

    // --- FIRST LAUNCH / TUTORIAL ---
    fun getFirstLaunchCompletedSync(): Boolean
    suspend fun setFirstLaunchCompleted(completed: Boolean)
    fun getTutorialCompletedSync(): Boolean
    suspend fun setTutorialCompleted(completed: Boolean)
}
