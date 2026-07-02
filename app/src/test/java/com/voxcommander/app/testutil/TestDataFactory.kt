package com.voxcommander.app.testutil

import com.voxcommander.app.domain.intent.model.FastMapRule
import com.voxcommander.app.domain.intent.model.NluIntent
import com.voxcommander.app.domain.intent.taxonomy.IntentTaxonomy

/**
 * Object Mother / Test Data Factory for Vox Commander tests.
 * Centralizes object creation with sensible defaults to improve test readability
 * and maintainability when data structures change.
 */
object TestDataFactory {

    // --- NLU INTENTS ---

    fun createNluIntent(
        actionVerb: String = IntentTaxonomy.Actions.PLAY,
        logicalSubject: String? = null,
        modifiers: List<String> = emptyList(),
        contextWords: List<String> = emptyList(),
        domain: String = IntentTaxonomy.Domains.AUDIO,
        action: String = IntentTaxonomy.Actions.PLAY,
        targetApp: String? = null,
        category: String? = null,
        confidence: Float = 1.0f,
        extras: Map<String, String> = emptyMap(),
        intentAction: String? = null,
        uriTemplate: String? = null,
        mediaControlType: String? = null
    ) = NluIntent(
        actionVerb = actionVerb,
        logicalSubject = logicalSubject,
        modifiers = modifiers,
        contextWords = contextWords,
        domain = domain,
        action = action,
        targetApp = targetApp,
        category = category,
        confidence = confidence,
        extras = extras,
        intentAction = intentAction,
        uriTemplate = uriTemplate,
        mediaControlType = mediaControlType
    )

    fun createPlayMusicIntent(
        artist: String? = "Smiley",
        track: String? = "Perfect",
        targetApp: String? = null
    ): NluIntent {
        val subject = listOfNotNull(artist, track).filter { it.isNotBlank() }.joinToString(" ")
        return createNluIntent(
            actionVerb = IntentTaxonomy.Actions.PLAY,
            logicalSubject = subject.ifBlank { null },
            domain = IntentTaxonomy.Domains.AUDIO,
            action = IntentTaxonomy.Actions.PLAY,
            targetApp = targetApp
        )
    }

    fun createNavigateIntent(
        destination: String = "Brasov",
        targetApp: String? = null
    ): NluIntent {
        return createNluIntent(
            actionVerb = IntentTaxonomy.Actions.NAVIGATE,
            logicalSubject = destination,
            domain = IntentTaxonomy.Domains.MAPS,
            action = IntentTaxonomy.Actions.NAVIGATE,
            targetApp = targetApp
        )
    }

    // --- FAST MAP RULES ---

    fun createFastMapRule(
        id: Long = 1L,
        allWords: List<String> = emptyList(),
        triggerWords: List<String> = emptyList(),
        queryWords: List<String> = emptyList(),
        targetPackage: String = "",
        intentAction: String = "",
        uriTemplate: String? = null,
        lazyQuery: Boolean = false,
        sortOrder: Int = 0,
        isActive: Boolean = true,
        domain: String = "custom",
        action: String = "launch",
        mediaControlType: String = "active_session"
    ) = FastMapRule(
        id = id,
        allWords = allWords,
        triggerWords = triggerWords,
        queryWords = queryWords,
        targetPackage = targetPackage,
        intentAction = intentAction,
        uriTemplate = uriTemplate,
        lazyQuery = lazyQuery,
        sortOrder = sortOrder,
        isActive = isActive,
        domain = domain,
        action = action,
        mediaControlType = mediaControlType
    )

    fun createAudioPlayRule(
        triggerWords: List<String> = listOf("pune", "muzica"),
        queryWords: List<String> = emptyList(),
        lazyQuery: Boolean = true,
        targetPackage: String = "com.spotify.music"
    ) = createFastMapRule(
        allWords = triggerWords + queryWords,
        triggerWords = triggerWords,
        queryWords = queryWords,
        lazyQuery = lazyQuery,
        targetPackage = targetPackage,
        domain = IntentTaxonomy.Domains.AUDIO,
        action = IntentTaxonomy.Actions.PLAY
    )

    fun createNavigationRule(
        triggerWords: List<String> = listOf("du-ma", "la"),
        queryWords: List<String> = emptyList(),
        lazyQuery: Boolean = true,
        targetPackage: String = "com.waze",
        uriTemplate: String? = "waze://?q={destination}&navigate=yes"
    ) = createFastMapRule(
        allWords = triggerWords + queryWords,
        triggerWords = triggerWords,
        queryWords = queryWords,
        lazyQuery = lazyQuery,
        targetPackage = targetPackage,
        domain = IntentTaxonomy.Domains.MAPS,
        action = IntentTaxonomy.Actions.NAVIGATE,
        uriTemplate = uriTemplate
    )

    fun createTransportControlRule(
        triggerWords: List<String> = listOf("pune", "play"),
        targetPackage: String = "",
        action: String = IntentTaxonomy.Actions.PLAY,
        mediaControlType: String = "audio_button"
    ) = createFastMapRule(
        allWords = triggerWords,
        triggerWords = triggerWords,
        lazyQuery = false,
        targetPackage = targetPackage,
        domain = IntentTaxonomy.Domains.AUDIO,
        action = action,
        mediaControlType = mediaControlType
    )

    fun createQueryOnlyRule(
        queryWords: List<String> = listOf("ceasul"),
        targetPackage: String = "com.voxcommander.app",
        domain: String = IntentTaxonomy.Domains.SYSTEM,
        action: String = "status"
    ) = createFastMapRule(
        allWords = queryWords,
        triggerWords = emptyList(),
        queryWords = queryWords,
        lazyQuery = false,
        targetPackage = targetPackage,
        domain = domain,
        action = action
    )

    fun createMessagingRule(
        triggerWords: List<String> = listOf("trimite", "mesaj", "la"),
        targetPackage: String = "com.whatsapp"
    ) = createFastMapRule(
        allWords = triggerWords,
        triggerWords = triggerWords,
        lazyQuery = true,
        targetPackage = targetPackage,
        domain = IntentTaxonomy.Domains.MESSAGING,
        action = IntentTaxonomy.Actions.SEND
    )

    fun createSettingsRule(
        triggerWords: List<String> = listOf("volum", "up"),
        action: String = IntentTaxonomy.Actions.VOLUME_UP
    ) = createFastMapRule(
        allWords = triggerWords,
        triggerWords = triggerWords,
        lazyQuery = false,
        domain = IntentTaxonomy.Domains.SETTINGS,
        action = action
    )

    // --- REMOTE MODEL ITEMS ---

    fun createRemoteModelItem(
        id: String = "base",
        label: String = "Whisper Base",
        path: String = "models/base.bin",
        sizeMb: Int = 74,
        sizeLabel: String? = null,
        langCode: String? = null,
        engineType: String? = "stt_whisper",
        isRemote: Boolean = true
    ) = com.voxcommander.app.data.remote.RemoteModelItem(
        id = id,
        label = label,
        path = path,
        size_mb = sizeMb,
        size_label = sizeLabel,
        lang_code = langCode,
        engine_type = engineType,
        is_remote = isRemote
    )

    fun createVirtualModelItem(
        id: String = "google",
        label: String = "Google",
        engineType: String = "stt_google",
        sizeDescription: String = "Cloud API",
        url: String = "",
        langCode: String? = null,
        isBuiltIn: Boolean = true
    ) = com.voxcommander.app.data.remote.VirtualModelItem(
        id = id,
        label = label,
        engineType = engineType,
        sizeDescription = sizeDescription,
        url = url,
        langCode = langCode,
        isBuiltIn = isBuiltIn
    )

    fun createBundledModelItem(
        id: String = "alexa_v0.1",
        label: String = "Alexa",
        path: String = "openwakeword/alexa_v0.1.onnx",
        sizeMb: Int = 1,
        engineType: String? = "wake_openwakeword"
    ) = createRemoteModelItem(
        id = id,
        label = label,
        path = path,
        sizeMb = sizeMb,
        engineType = engineType,
        isRemote = false
    )

    // --- APP SETTINGS BUILDERS ---

    fun createAppSettings(
        voiceProcessor: String = com.voxcommander.app.utils.Strings.Processors.GOOGLE,
        voiceLanguage: String = "en",
        aiProcessor: String = com.voxcommander.app.utils.Strings.AiProcessors.OPENAI,
        cloudIntelligenceEnabled: Boolean = true,
        activeVoiceModelId: String? = null,
        activeIntentModelId: String? = null,
        downloadedModelIds: Set<String> = emptySet(),
        defaultIntentFallbackProcessor: String? = null,
        defaultIntentFallbackModel: String? = null,
        defaultVoiceFallbackProcessor: String? = null,
        defaultVoiceFallbackModel: String? = null,
        vulkanProbeDone: Boolean = true,
        vulkanIncompatible: Boolean = false,
        geminiIncompatible: Boolean = false,
        geminiApiKey: String? = null,
        customModelPaths: Map<String, String> = emptyMap(),
        engineModelSelections: Map<String, String> = emptyMap(),
        wakeWordModelPath: String? = null,
        wakeWordEngineType: String = "vosk",
        isWhisperSystemEnabled: Boolean = false
    ) = com.voxcommander.app.data.preferences.AppSettings(
        voiceProcessor = voiceProcessor,
        voiceLanguage = voiceLanguage,
        aiProcessor = aiProcessor,
        cloudIntelligenceEnabled = cloudIntelligenceEnabled,
        activeVoiceModelId = activeVoiceModelId,
        activeIntentModelId = activeIntentModelId,
        downloadedModelIds = downloadedModelIds,
        defaultIntentFallbackProcessor = defaultIntentFallbackProcessor,
        defaultIntentFallbackModel = defaultIntentFallbackModel,
        defaultVoiceFallbackProcessor = defaultVoiceFallbackProcessor,
        defaultVoiceFallbackModel = defaultVoiceFallbackModel,
        vulkanProbeDone = vulkanProbeDone,
        vulkanIncompatible = vulkanIncompatible,
        geminiIncompatible = geminiIncompatible,
        geminiApiKey = geminiApiKey,
        customModelPaths = customModelPaths,
        engineModelSelections = engineModelSelections,
        wakeWordModelPath = wakeWordModelPath,
        wakeWordEngineType = wakeWordEngineType,
        isWhisperSystemEnabled = isWhisperSystemEnabled
    )

    fun createSettingsWithWhisperVulkan(
        activeVoiceModelId: String? = "base",
        downloadedModelIds: Set<String> = setOf("base"),
        vulkanProbeDone: Boolean = true
    ) = createAppSettings(
        voiceProcessor = com.voxcommander.app.utils.Strings.Processors.WHISPER_VULKAN,
        activeVoiceModelId = activeVoiceModelId,
        downloadedModelIds = downloadedModelIds,
        vulkanProbeDone = vulkanProbeDone
    )

    fun createSettingsWithLlmEngine(
        aiProcessor: String = "nlu_llm",
        activeIntentModelId: String? = "qwen2.5-1.5b-q8",
        downloadedModelIds: Set<String> = emptySet(),
        fallbackProcessor: String? = null,
        fallbackModel: String? = null
    ) = createAppSettings(
        aiProcessor = aiProcessor,
        activeIntentModelId = activeIntentModelId,
        downloadedModelIds = downloadedModelIds,
        defaultIntentFallbackProcessor = fallbackProcessor,
        defaultIntentFallbackModel = fallbackModel
    )

    fun createSettingsWithGeminiNative(
        geminiIncompatible: Boolean = false
    ) = createAppSettings(
        aiProcessor = com.voxcommander.app.utils.Strings.AiProcessors.GEMINI_NATIVE,
        geminiIncompatible = geminiIncompatible
    )

    fun createSettingsWithGeminiCloud(
        geminiApiKey: String? = "test-key",
        cloudIntelligenceEnabled: Boolean = true
    ) = createAppSettings(
        aiProcessor = com.voxcommander.app.utils.Strings.AiProcessors.GEMINI_CLOUD,
        geminiApiKey = geminiApiKey,
        cloudIntelligenceEnabled = cloudIntelligenceEnabled
    )
}
