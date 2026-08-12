package com.voxapps.commander.state

import androidx.compose.runtime.Immutable

import android.content.Context
import com.voxapps.commander.data.preferences.AppSettings
import com.voxapps.commander.domain.model.AppModel
import com.voxapps.commander.data.remote.EngineRuntime
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
    /** The custom model the current voice selection would load, if one was imported for it. Was
     *  two fields, each named after an engine that happened to accept imports — so a third such
     *  engine had nowhere to put its path, and readers picked between them by file extension. */
    val customVoiceModelPath: String?,

    // --- INTENT SETTINGS ---
    val aiProcessor: String,
    val activeIntentModelId: String?,
    val cloudIntelligenceEnabled: Boolean,
    val googleServicesEnabled: Boolean,
    
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
    val useRemoteSchemas: Boolean = true,

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
            // Whose declaration answers for this selection. See SttEngines.backingEngineKey — every
            // processor is its own engine key except the one that is a mode of another engine.
            val voiceEngineKey =
                com.voxapps.commander.domain.engine.SttEngines.backingEngineKey(voiceProcessor)

            // A directory-packaged engine keeps one custom model per language; a single-file engine
            // keeps one. Both live under the engine's own key, so the selection resolves its own.
            val customVoiceModelPath = settings.getCustomModelPath(
                voiceEngineKey,
                modelFilterLang.takeIf {
                    com.voxapps.commander.data.remote.RemoteModelRegistry.isPerLanguage(voiceEngineKey)
                }
            )

            /*
             * Ready means "this engine has something to run with", and what that takes is declared.
             *
             * It used to be asked as a chain of names: Google and the Whisper API answered true by
             * being listed, Vulkan by being listed again, and everything else was sorted into
             * "whisper-like" or "vosk-like" by file extension — which read the *whisper* custom path
             * for any engine that was not zip-packaged, so a third local engine would have been
             * judged by a model it does not own.
             */
            val voiceModelReady = when (
                com.voxapps.commander.data.remote.RemoteModelRegistry.runtimeOf(voiceEngineKey)
            ) {
                // Nothing to have on disk: the OS supplies it, or an endpoint does.
                EngineRuntime.CLOUD,
                EngineRuntime.ANDROID_LOCAL,
                EngineRuntime.DEVICE_BUILTIN -> true

                else ->
                    if (!customVoiceModelPath.isNullOrBlank()) java.io.File(customVoiceModelPath).exists()
                    else !activeVoiceModelId.isNullOrBlank() && settings.isModelDownloaded(activeVoiceModelId)
            }

            // Calculate intentModelReady
            val intentModelReady = when (settings.aiProcessor) {
                Strings.AiProcessors.OPENAI -> true
                else -> {
                    // JSON-defined LLM engines
                    if (com.voxapps.commander.data.remote.RemoteModelRegistry.isLlmEngine(settings.aiProcessor)) {
                        settings.activeIntentModelId != null && settings.isModelDownloaded(settings.activeIntentModelId)
                    } else false
                }
            }

            return AppState(
                language = settings.language,
                voiceProcessor = voiceProcessor,
                voiceLanguage = settings.voiceLanguage,
                voiceLanguageAutoDetect = settings.voiceLanguageAutoDetect,
                modelFilterLang = modelFilterLang,
                activeVoiceModelId = activeVoiceModelId,
                activeWakeModelId = settings.activeWakeModelId,
                customVoiceModelPath = customVoiceModelPath,
                aiProcessor = settings.aiProcessor,
                activeIntentModelId = settings.activeIntentModelId,
                cloudIntelligenceEnabled = settings.cloudIntelligenceEnabled,
                googleServicesEnabled = settings.googleServicesEnabled,
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
                useRemoteSchemas = settings.useRemoteSchemas,
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
            customVoiceModelPath = null,
            aiProcessor = "",
            activeIntentModelId = null,
            cloudIntelligenceEnabled = false,
            googleServicesEnabled = false,
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
            useRemoteSchemas = true,
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
