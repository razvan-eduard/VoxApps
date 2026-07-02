package com.voxcommander.app.state

import android.content.Context
import com.voxcommander.app.data.preferences.AppSettings
import com.voxcommander.app.domain.model.AppModel
import com.voxcommander.app.utils.Strings

/**
 * Centralized application state data class.
 * Contains all UI-relevant state in a single immutable object.
 * This is the reactive Single Source of Truth (SSOT).
 */
data class AppState(
    // --- UI LANGUAGE ---
    val language: String,

    // --- VOICE SETTINGS ---
    val voiceProcessor: String,
    val voiceLanguage: String,
    val modelFilterLang: String,
    val activeVoiceModelId: String?,
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
    val picovoiceAccessKey: String?,
    val wakeWordSensitivity: String,
    val isWakeWordServiceListening: Boolean,
    val isVerboseLoggingEnabled: Boolean,
    val isExperimentalVulkanEnabled: Boolean,
    val isWhisperSystemEnabled: Boolean,
    val downloadPreference: String,

    // --- TTS SETTINGS ---
    val ttsEnabled: Boolean,
    val ttsSpeechRate: Float,
    val ttsPitch: Float,
    val ttsAudioFocusMode: String,
    
    // --- API SETTINGS ---
    val apiKey: String?,
    val geminiApiKey: String?,
    
    // --- RUNTIME STATE ---
    val voiceState: VoiceState,
    val wakeWordDetected: Boolean,
    
    // --- FALLBACK MODEL SETTINGS ---
    val defaultVoiceFallbackProcessor: String?,
    val defaultVoiceFallbackModel: String?,
    val defaultIntentFallbackProcessor: String?,
    val defaultIntentFallbackModel: String?,

    // --- DYNAMIC MODEL REGISTRY (Reconstructed from JSON Cache) ---
    val availableModels: Map<String, List<AppModel>> = emptyMap(),
    val downloadedModelIds: Set<String> = emptySet(),
    
    // --- UI SYNC ---
    val refreshTrigger: Int = 0,
    val canDrawOverlays: Boolean = false,
    val hasMicrophonePermission: Boolean = false,
    val hasNotificationPermission: Boolean = false,
    val hasLocationPermission: Boolean = false,
    
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
            context: Context,
            availableModels: Map<String, List<AppModel>>,
            voiceState: VoiceState = VoiceState.IDLE,
            wakeWordDetected: Boolean = false,
            isWakeWordServiceListening: Boolean = false,
            refreshTrigger: Int = 0
        ): AppState {
            val voiceProcessor = settings.voiceProcessor
            val modelFilterLang = settings.modelFilterLang
            val activeVoiceModelId = settings.activeVoiceModelId
            val whisperKey = com.voxcommander.app.data.remote.RemoteModelRegistry.getEngineKeyByExtension(".bin")
            val voskKey = com.voxcommander.app.data.remote.RemoteModelRegistry.getEngineKeyByExtension(".zip")
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
                    if (!com.voxcommander.app.data.remote.RemoteModelRegistry.isZipEngine(voiceProcessor)) {
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
                Strings.AiProcessors.GEMINI_CLOUD -> {
                    !settings.geminiApiKey.isNullOrBlank()
                }
                Strings.AiProcessors.OPENAI -> true
                else -> {
                    // JSON-defined LLM engines
                    if (com.voxcommander.app.data.remote.RemoteModelRegistry.isLlmEngine(settings.aiProcessor)) {
                        settings.activeIntentModelId != null && settings.isModelDownloaded(settings.activeIntentModelId)
                    } else false
                }
            }

            // Load custom Vosk paths
            val customVoskModelPaths = mutableMapOf<String, String>()
            val languages = listOf("en", "ro", "de", "fr")
            languages.forEach { lang ->
                voskKey?.let { key ->
                    settings.getCustomModelPath(key, lang)?.let { path ->
                        customVoskModelPaths[lang] = path
                    }
                }
            }

            return AppState(
                language = settings.language,
                voiceProcessor = voiceProcessor,
                voiceLanguage = settings.voiceLanguage,
                modelFilterLang = modelFilterLang,
                activeVoiceModelId = activeVoiceModelId,
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
                picovoiceAccessKey = settings.picovoiceAccessKey,
                wakeWordSensitivity = settings.wakeWordSensitivity,
                isWakeWordServiceListening = isWakeWordServiceListening,
                isVerboseLoggingEnabled = settings.verboseLoggingEnabled,
                isExperimentalVulkanEnabled = settings.experimentalVulkanEnabled,
                isWhisperSystemEnabled = settings.isWhisperSystemEnabled,
                downloadPreference = settings.downloadPreference,
                ttsEnabled = settings.ttsEnabled,
                ttsSpeechRate = settings.ttsSpeechRate,
                ttsPitch = settings.ttsPitch,
                ttsAudioFocusMode = settings.ttsAudioFocusMode,
                apiKey = settings.apiKey,
                geminiApiKey = settings.geminiApiKey,
                voiceState = voiceState,
                wakeWordDetected = wakeWordDetected,
                defaultVoiceFallbackProcessor = settings.defaultVoiceFallbackProcessor,
                defaultVoiceFallbackModel = settings.defaultVoiceFallbackModel,
                defaultIntentFallbackProcessor = settings.defaultIntentFallbackProcessor,
                defaultIntentFallbackModel = settings.defaultIntentFallbackModel,
                availableModels = availableModels,
                downloadedModelIds = settings.downloadedModelIds,
                refreshTrigger = refreshTrigger,
                canDrawOverlays = com.voxcommander.app.utils.PermissionUtils.canDrawOverlays(context),
                hasMicrophonePermission = com.voxcommander.app.utils.PermissionUtils.hasMicrophonePermission(context),
                hasNotificationPermission = com.voxcommander.app.utils.PermissionUtils.hasNotificationPermission(context),
                hasLocationPermission = com.voxcommander.app.domain.search.LocationHelper.hasLocationPermission(context),
                voiceModelReady = voiceModelReady,
                intentModelReady = intentModelReady
            )
        }

        fun initial(): AppState = AppState(
            language = Strings.Preferences.DEFAULT_LANGUAGE,
            voiceProcessor = "",
            voiceLanguage = Strings.Preferences.DEFAULT_LANGUAGE,
            modelFilterLang = Strings.Preferences.DEFAULT_LANGUAGE,
            activeVoiceModelId = null,
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
            picovoiceAccessKey = null,
            wakeWordSensitivity = "medium",
            isWakeWordServiceListening = false,
            isVerboseLoggingEnabled = false,
            isExperimentalVulkanEnabled = false,
            isWhisperSystemEnabled = false,
            downloadPreference = "wifi_and_metered",
            ttsEnabled = true,
            ttsSpeechRate = 1.0f,
            ttsPitch = 1.0f,
            ttsAudioFocusMode = "duck",
            apiKey = null,
            geminiApiKey = null,
            voiceState = VoiceState.IDLE,
            wakeWordDetected = false,
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
