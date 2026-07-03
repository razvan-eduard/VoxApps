package com.voxcommander.app.data.preferences

import com.voxcommander.app.utils.Strings

/**
 * Immutable snapshot of all persisted application settings.
 * This is the reactive payload emitted by SettingsRepository.
 * AppStateManager combines this with runtime state to produce AppState.
 */
data class AppSettings(
    // --- API / CLOUD ---
    val apiKey: String? = null,
    val geminiApiKey: String? = null,

    // --- LANGUAGE ---
    val language: String = Strings.Preferences.DEFAULT_LANGUAGE,
    val voiceLanguage: String = Strings.Preferences.DEFAULT_LANGUAGE,
    val voiceLanguageAutoDetect: Boolean = false,
    val modelFilterLang: String = Strings.Preferences.DEFAULT_LANGUAGE,

    // --- VOICE ENGINE ---
    val voiceProcessor: String = Strings.Preferences.DEFAULT_PROCESSOR,
    val activeVoiceModelId: String? = null,

    // --- INTENT ENGINE ---
    val aiProcessor: String = Strings.Preferences.DEFAULT_PROCESSOR,
    val activeIntentModelId: String? = null,
    val cloudIntelligenceEnabled: Boolean = false,

    // --- PER-ENGINE MODEL SELECTIONS ---
    val engineModelSelections: Map<String, String> = emptyMap(),

    // --- WAKE WORD ---
    val wakeWord: String = "hi vosk",
    val wakeWordEnabled: Boolean = false,
    val wakeWordModelPath: String? = null,
    val commandQueueEnabled: Boolean = true,
    val wakeWordProfileJson: String? = null,
    val wakeWordEngineType: String = "vosk",
    val picovoiceAccessKey: String? = null,
    val wakeWordSensitivity: String = "medium", // "low", "medium", "high"

    // --- OFFLINE FALLBACK ---
    val offlineFallbackTimeout: Int = 10,
    val defaultOfflineModel: String = "tiny",
    val defaultVoiceFallbackProcessor: String? = null,
    val defaultVoiceFallbackModel: String? = null,
    val defaultIntentFallbackProcessor: String? = null,
    val defaultIntentFallbackModel: String? = null,

    // --- LOGGING ---
    val logLevel: String = "LOGCAT_ONLY",
    val verboseLoggingEnabled: Boolean = false,

    // --- VULKAN ---
    val vulkanIncompatible: Boolean = false,
    val vulkanProbeDone: Boolean = false,
    val vulkanRuntimeAttempt: Boolean = false,
    val vulkanRuntimeVerified: Boolean = false,
    val experimentalVulkanEnabled: Boolean = false,

    // --- WHISPER ENGINE (DLC) ---
    val isWhisperSystemEnabled: Boolean = false,

    // --- GEMINI ---
    val geminiIncompatible: Boolean = false,

    // --- REMOTE REPOSITORY ---
    val modelRepoBaseUrl: String = Strings.Preferences.DEFAULT_MODEL_REPO_URL,
    val modelsJsonCache: String? = null,

    // --- DEFAULT APPS PER DOMAIN ---
    /** Map of domain -> package name. e.g. "audio" -> "com.spotify.music" */
    val defaultAppPackages: Map<String, String> = emptyMap(),

    /** Map of domain -> list of package names the user selected for that domain. */
    val domainAppPackages: Map<String, List<String>> = emptyMap(),

    /** User-defined custom domain names (e.g. "notes_apps", "fitness"). */
    val customDomains: List<String> = emptyList(),

    /** Map of domain -> filter mode ("all", "user", "system"). */
    val domainAppFilters: Map<String, String> = emptyMap(),

    /** Cached list of installed apps as JSON (for fast startup). Null = not scanned yet. */
    val appCacheJson: String? = null,

    // --- MODEL DOWNLOAD STATE ---
    val downloadedModelIds: Set<String> = emptySet(),
    val customModelPaths: Map<String, String> = emptyMap(),

    // --- DOWNLOAD PREFERENCE ---
    /** "wifi_only" or "wifi_and_metered" */
    val downloadPreference: String = "wifi_and_metered",

    // --- MEDIA / EXTERNAL SERVICES ---
    val spotifyClientId: String? = null,
    val pipedApiUrl: String? = null,
    val pipedRegion: String? = null,

    // --- SEARCH PROVIDER API KEYS ---
    /** Map of provider name -> API key (stored encrypted) */
    val searchProviderApiKeys: Map<String, String> = emptyMap(),

    // --- TTS ---
    val ttsEnabled: Boolean = true,
    val ttsSpeechRate: Float = 1.0f,
    val ttsPitch: Float = 1.0f,
    val ttsAudioFocusMode: String = "duck", // "none", "duck", "pause"
    val overlayTextSize: Float = 1.0f, // multiplier for overlay text size

    // --- APP ALIASES ---
    val appAliasRules: List<AppAliasRule> = emptyList(),

) {
    /**
     * Key for custom model path: "engineKey" or "engineKey_langCode"
     */
    fun customModelPathKey(engineKey: String, langCode: String? = null): String {
        return if (langCode != null) "${engineKey}_$langCode" else engineKey
    }

    fun isModelDownloaded(modelId: String): Boolean = modelId in downloadedModelIds

    fun getCustomModelPath(engineKey: String, langCode: String? = null): String? {
        return customModelPaths[customModelPathKey(engineKey, langCode)]
    }
}
