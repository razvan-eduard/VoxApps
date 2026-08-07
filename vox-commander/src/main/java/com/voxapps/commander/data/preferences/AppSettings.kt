package com.voxapps.commander.data.preferences

import androidx.compose.runtime.Immutable

import com.voxapps.commander.utils.Strings

/**
 * Immutable snapshot of all persisted application settings.
 * This is the reactive payload emitted by SettingsRepository.
 * AppStateManager combines this with runtime state to produce AppState.
 */
@Immutable
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
    val wakeWordAecEnabled: Boolean = false, // AEC for wake word during media/TTS playback
    val wakeWordMusicDuckEnabled: Boolean = true, // require higher OpenWakeWord confidence during music playback
    val sttSensitivity: String = "medium", // "low", "medium", "high" — microphone sensitivity for STT listening

    // --- OFFLINE FALLBACK ---
    val offlineFallbackTimeout: Int = 10,
    val defaultOfflineModel: String = "tiny",
    val defaultVoiceFallbackProcessor: String? = null,
    val defaultVoiceFallbackModel: String? = null,
    val defaultIntentFallbackProcessor: String? = null,
    val defaultIntentFallbackModel: String? = null,

    // --- LOGGING --- (same shape as every other Vox app's pair, see com.voxapps.logging.Logger)
    val debugLoggingEnabled: Boolean = true,
    val debugToastsEnabled: Boolean = false,
    val themeDarkMode: String = "SYSTEM", // "SYSTEM", "LIGHT", "DARK"
    val themeColored: Boolean = false,    // true = Material You dynamic color (Android 12+)

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
    val youtubeUrlEngine: String = "piped", // "piped" or "newpipe"

    /** Packages that trigger return-to-previous-app after intent execution. */
    val returnAfterActionApps: List<String> = emptyList(),

    /** Allow external automation apps (MacroDroid, Tasker) to trigger voice assistant via broadcast. */
    val externalTriggerEnabled: Boolean = true,

    // --- SEARCH PROVIDER API KEYS ---
    /** Map of provider name -> API key (stored encrypted) */
    val searchProviderApiKeys: Map<String, String> = emptyMap(),

    // --- TTS ---
    val ttsEnabled: Boolean = true,
    /** The TTS picker stores whatever `getEngineKeysByType("tts")` returned, so this holds a
     *  models.json engine key ("piper_tts"), not the short name. Legacy spellings that may survive
     *  in an old backup are normalised on read by SettingsRepositoryImpl.normalizeEngineKey. */
    val ttsEngineType: String = "android",
    val ttsSpeechRate: Float = 1.0f,
    val ttsPitch: Float = 1.0f,
    val ttsAudioFocusMode: String = "duck", // "none", "duck", "pause"
    val overlayTextSize: Float = 1.0f, // multiplier for overlay text size
    /** The user's explicitly picked Piper voice model id (e.g. "vits-piper-en_US-lessac-medium").
     *  null = no explicit pick yet, PiperTtsEngine falls back to its own on-disk heuristic. */
    val piperVoiceModelId: String? = null,

    // --- APP ALIASES ---
    val appAliasRules: List<AppAliasRule> = emptyList(),

    // --- LOCATION (shared :core:location module) ---
    /** "Home town" fallback, used when GPS/cache are unavailable or [locationAlwaysUseHomeTown] is on. */
    val locationHomeTownLat: Double? = null,
    val locationHomeTownLon: Double? = null,
    /** [com.voxapps.location.LocationCacheTtl] enum name, e.g. "ONE_DAY". */
    val locationCacheTtl: String = "ONE_DAY",
    val locationAlwaysUseHomeTown: Boolean = false,

    // --- BACKUP & RESTORE (local, shared :core:backup module's VoxBackupSettingsCard) ---
    // Mirrors vox-hub's AppBackupConfig shape/names — this is the same concept, just persisted
    // locally for this app's own "back up to a file I pick right now" button, independent of any
    // Hub-triggered IPC export (which always carries its own explicit scope/secrets parameters).
    val backupIncludeSettings: Boolean = true,
    val backupIncludeData: Boolean = true,
    val backupIncludeApiKeys: Boolean = false,
    /** Wire-format string per [com.voxapps.ipc.VoxIpc.IMPORT_MODE_MERGE] etc. (same lowercase
     *  convention as vox-hub's `HubSettings.importMode`, parsed via
     *  [com.voxapps.backup.VoxImportMode.fromWireValue]) — governs only this app's own local
     *  restore-from-file button. */
    val backupImportMode: String = "merge",

    // --- FIRST LAUNCH / TUTORIAL ---
    val firstLaunchCompleted: Boolean = false,
    val tutorialCompleted: Boolean = false,

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
