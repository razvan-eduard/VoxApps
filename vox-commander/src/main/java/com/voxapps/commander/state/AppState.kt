package com.voxapps.commander.state

import androidx.compose.runtime.Immutable

import android.content.Context
import com.voxapps.commander.data.preferences.AppSettings
import com.voxapps.commander.domain.model.AppModel
import com.voxapps.commander.utils.Strings

/**
 * Centralized application state data class.
 * Contains all UI-relevant state in a single immutable object.
 * This is the reactive Single Source of Truth (SSOT).
 */
@Immutable
data class AppState(
    // --- UI LANGUAGE ---
    val language: String,

    // --- VOICE SETTINGS ---
    val voiceProcessor: String,
    val voiceLanguage: String,
    val voiceLanguageAutoDetect: Boolean,
    val modelFilterLang: String,
    val activeVoiceModelId: String?,
    val activeWakeModelId: String?,
    val customWhisperModelPath: String?,
    val customVoskModelPaths: Map<String, String>,

    // --- INTENT SETTINGS ---
    val aiProcessor: String,
    val activeIntentModelId: String?,
    val cloudIntelligenceEnabled: Boolean,
    
    // --- WAKE WORD SETTINGS ---
    val wakeWord: String,
    val wakeWordEnabled: Boolean,
    val wakeWordModelPath: String?,
    val commandQueueEnabled: Boolean,
    val wakeWordProfileJson: String?,
    val wakeWordEngineType: String,
    val wakeWordSensitivity: String,
    val wakeWordAecEnabled: Boolean,
    val wakeWordMusicDuckEnabled: Boolean,
    val sttSensitivity: String,
    val themeDarkMode: String,
    val themeColored: Boolean,
    val isWakeWordServiceListening: Boolean,
    val isVerboseLoggingEnabled: Boolean,
    val isExperimentalVulkanEnabled: Boolean,
    val isWhisperSystemEnabled: Boolean,
    val downloadPreference: String,

    // --- TTS SETTINGS ---
    val ttsEnabled: Boolean,
    val ttsEngineType: String = "android",
    val ttsSpeechRate: Float,
    val ttsPitch: Float,
    val ttsAudioFocusMode: String,
    val overlayTextSize: Float,
    val piperVoiceModelId: String? = null,
    val appAliasRules: List<com.voxapps.commander.data.preferences.AppAliasRule> = emptyList(),
    
    // --- API SETTINGS ---
    /** The encrypted store's contents, carried whole rather than unpacked into loose fields:
     *  the UI reads credentials the same way it reads every other piece of state, through
     *  [AppStateManager], and there is one thing to pass on when it needs them all. */
    val credentials: com.voxapps.commander.data.preferences.Credentials,
    
    // --- RUNTIME STATE ---
    val voiceState: VoiceState,
    
    // --- FALLBACK MODEL SETTINGS ---
    val defaultVoiceFallbackProcessor: String?,
    val defaultVoiceFallbackModel: String?,
    val defaultIntentFallbackProcessor: String?,
    val defaultIntentFallbackModel: String?,

    /** Whether the app asks the repository for newer schemas at startup. */
    val schemaAutoUpdate: Boolean = true,

    // --- SEARCH SETTINGS ---
    /** Category -> chosen search provider, empty for a category left on its declared default. */
    val searchProviderSelections: Map<String, String> = emptyMap(),

    // --- DYNAMIC MODEL REGISTRY (Reconstructed from JSON Cache) ---
    val availableModels: Map<String, List<AppModel>> = emptyMap(),
    val downloadedModelIds: Set<String> = emptySet(),
    
    // --- UI SYNC ---
    val refreshTrigger: Int = 0,
    val canDrawOverlays: Boolean = false,
    val hasMicrophonePermission: Boolean = false,
    val hasNotificationPermission: Boolean = false,
    val hasLocationPermission: Boolean = false,
    val isIgnoringBatteryOptimizations: Boolean = false,

    // --- DERIVED PROPERTIES (Calculated from base state) ---
    val voiceModelReady: Boolean,
    val intentModelReady: Boolean
) {
    fun isModelDownloaded(modelId: String): Boolean = modelId in downloadedModelIds
    companion object {
        /**
         * Derives AppState from an AppSettings snapshot + runtime state.
         * This is called reactively whenever AppSettings or runtime state changes.
         */
        fun fromAppSettings(
            settings: AppSettings,
            credentials: com.voxapps.commander.data.preferences.Credentials,
            context: Context,
            availableModels: Map<String, List<AppModel>>,
            voiceState: VoiceState = VoiceState.IDLE,
            isWakeWordServiceListening: Boolean = false,
            refreshTrigger: Int = 0
        ): AppState {
            val voiceProcessor = settings.voiceProcessor
            val modelFilterLang = settings.modelFilterLang
            val activeVoiceModelId = settings.activeVoiceModelId
            val whisperKey = com.voxapps.commander.data.remote.RemoteModelRegistry.getEngineKeyByExtension(".bin")
            val voskKey = com.voxapps.commander.data.remote.RemoteModelRegistry.getEngineKeyByExtension(".zip")
            val customWhisperModelPath = whisperKey?.let { settings.getCustomModelPath(it) }

            // Calculate voiceModelReady
            val voiceModelReady = when (voiceProcessor) {
                Strings.Processors.GOOGLE,
                Strings.Processors.WHISPER_API -> true
                Strings.Processors.WHISPER_VULKAN -> {
                    val isDownloaded = activeVoiceModelId != null && settings.isModelDownloaded(activeVoiceModelId)
                    isDownloaded || !customWhisperModelPath.isNullOrBlank()
                }
                else -> {
                    // JSON-defined voice engines — check by type
                    if (!com.voxapps.commander.data.remote.RemoteModelRegistry.isZipEngine(voiceProcessor)) {
                        // Whisper-like (.bin) engine
                        val isDownloaded = activeVoiceModelId != null && settings.isModelDownloaded(activeVoiceModelId)
                        isDownloaded || !customWhisperModelPath.isNullOrBlank()
                    } else {
                        // Vosk-like (.zip) engine
                        val customPath = voskKey?.let { settings.getCustomModelPath(it, modelFilterLang) }
                        if (!customPath.isNullOrBlank()) {
                            java.io.File(customPath).exists()
                        } else {
                            !activeVoiceModelId.isNullOrBlank() && settings.isModelDownloaded(activeVoiceModelId)
                        }
                    }
                }
            }

            // Calculate intentModelReady
            val intentModelReady = when (settings.aiProcessor) {
                Strings.AiProcessors.GEMINI_NATIVE -> {
                    !settings.geminiIncompatible
                }
                Strings.AiProcessors.GEMINI_CLOUD -> credentials.has(Strings.AiProcessors.GEMINI_CLOUD)
                Strings.AiProcessors.OPENAI -> true
                else -> {
                    // JSON-defined LLM engines
                    if (com.voxapps.commander.data.remote.RemoteModelRegistry.isLlmEngine(settings.aiProcessor)) {
                        settings.activeIntentModelId != null && settings.isModelDownloaded(settings.activeIntentModelId)
                    } else false
                }
            }

            // Whatever languages models were actually imported for, rather than a list of four
            // written here that the store could always have outgrown.
            val customVoskModelPaths = voskKey?.let { settings.customModelPathsByLanguage(it) }.orEmpty()

            return AppState(
                language = settings.language,
                voiceProcessor = voiceProcessor,
                voiceLanguage = settings.voiceLanguage,
                voiceLanguageAutoDetect = settings.voiceLanguageAutoDetect,
                modelFilterLang = modelFilterLang,
                activeVoiceModelId = activeVoiceModelId,
                activeWakeModelId = settings.activeWakeModelId,
                customWhisperModelPath = customWhisperModelPath,
                customVoskModelPaths = customVoskModelPaths,
                aiProcessor = settings.aiProcessor,
                activeIntentModelId = settings.activeIntentModelId,
                cloudIntelligenceEnabled = settings.cloudIntelligenceEnabled,
                wakeWord = settings.wakeWord,
                wakeWordEnabled = settings.wakeWordEnabled,
                wakeWordModelPath = settings.wakeWordModelPath,
                commandQueueEnabled = settings.commandQueueEnabled,
                wakeWordProfileJson = settings.wakeWordProfileJson,
                wakeWordEngineType = settings.wakeWordEngineType,
                wakeWordSensitivity = settings.wakeWordSensitivity,
                wakeWordAecEnabled = settings.wakeWordAecEnabled,
                wakeWordMusicDuckEnabled = settings.wakeWordMusicDuckEnabled,
                sttSensitivity = settings.sttSensitivity,
                themeDarkMode = settings.themeDarkMode,
                themeColored = settings.themeColored,
                isWakeWordServiceListening = isWakeWordServiceListening,
                isVerboseLoggingEnabled = settings.debugLoggingEnabled,
                isExperimentalVulkanEnabled = settings.experimentalVulkanEnabled,
                isWhisperSystemEnabled = settings.isWhisperSystemEnabled,
                downloadPreference = settings.downloadPreference,
                ttsEnabled = settings.ttsEnabled,
                ttsEngineType = settings.ttsEngineType,
                ttsSpeechRate = settings.ttsSpeechRate,
                ttsPitch = settings.ttsPitch,
                ttsAudioFocusMode = settings.ttsAudioFocusMode,
                overlayTextSize = settings.overlayTextSize,
                piperVoiceModelId = settings.piperVoiceModelId,
                appAliasRules = settings.appAliasRules,
                credentials = credentials,
                schemaAutoUpdate = settings.schemaAutoUpdate,
                searchProviderSelections = settings.searchProviderSelections,
                voiceState = voiceState,
                defaultVoiceFallbackProcessor = settings.defaultVoiceFallbackProcessor,
                defaultVoiceFallbackModel = settings.defaultVoiceFallbackModel,
                defaultIntentFallbackProcessor = settings.defaultIntentFallbackProcessor,
                defaultIntentFallbackModel = settings.defaultIntentFallbackModel,
                availableModels = availableModels,
                downloadedModelIds = settings.downloadedModelIds,
                refreshTrigger = refreshTrigger,
                canDrawOverlays = com.voxapps.commander.utils.PermissionUtils.canDrawOverlays(context),
                hasMicrophonePermission = com.voxapps.commander.utils.PermissionUtils.hasMicrophonePermission(context),
                hasNotificationPermission = com.voxapps.commander.utils.PermissionUtils.hasNotificationPermission(context),
                hasLocationPermission = com.voxapps.commander.utils.PermissionUtils.hasLocationPermission(context),
                isIgnoringBatteryOptimizations = com.voxapps.commander.utils.PermissionUtils.isIgnoringBatteryOptimizations(context),
                voiceModelReady = voiceModelReady,
                intentModelReady = intentModelReady
            )
        }

        fun initial(): AppState = AppState(
            language = Strings.Preferences.DEFAULT_LANGUAGE,
            voiceProcessor = "",
            voiceLanguage = Strings.Preferences.DEFAULT_LANGUAGE,
            voiceLanguageAutoDetect = false,
            modelFilterLang = Strings.Preferences.DEFAULT_LANGUAGE,
            activeVoiceModelId = null,
            activeWakeModelId = null,
            customWhisperModelPath = null,
            customVoskModelPaths = emptyMap(),
            aiProcessor = "",
            activeIntentModelId = null,
            cloudIntelligenceEnabled = false,
            wakeWord = "",
            wakeWordEnabled = false,
            wakeWordModelPath = null,
            commandQueueEnabled = true,
            wakeWordProfileJson = null,
            wakeWordEngineType = "wake_vosk",
            wakeWordSensitivity = "medium",
            wakeWordAecEnabled = false,
            wakeWordMusicDuckEnabled = true,
            sttSensitivity = "medium",
            themeDarkMode = "SYSTEM",
            themeColored = false,
            isWakeWordServiceListening = false,
            isVerboseLoggingEnabled = false,
            isExperimentalVulkanEnabled = false,
            isWhisperSystemEnabled = false,
            downloadPreference = "wifi_and_metered",
            ttsEnabled = true,
            ttsEngineType = "android",
            ttsSpeechRate = 1.0f,
            ttsPitch = 1.0f,
            ttsAudioFocusMode = "duck",
            overlayTextSize = 1.0f,
            piperVoiceModelId = null,
            appAliasRules = emptyList(),
            credentials = com.voxapps.commander.data.preferences.Credentials(),
            schemaAutoUpdate = true,
            searchProviderSelections = emptyMap(),
            voiceState = VoiceState.IDLE,
            defaultVoiceFallbackProcessor = null,
            defaultVoiceFallbackModel = null,
            defaultIntentFallbackProcessor = null,
            defaultIntentFallbackModel = null,
            voiceModelReady = false,
            intentModelReady = false,
            downloadedModelIds = emptySet()
        )
    }
    
}
