package com.voxcommander.app.state

import android.content.Context
import com.voxcommander.app.data.remote.RemoteModelRegistry
import com.voxcommander.app.testutil.TestDataFactory
import com.voxcommander.app.utils.PermissionUtils
import com.voxcommander.app.utils.Strings
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppStateTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0

        mockkObject(com.voxcommander.app.utils.Logger)
        every { com.voxcommander.app.utils.Logger.log(any(), any()) } returns Unit

        mockkObject(RemoteModelRegistry)
        every { RemoteModelRegistry.getEngineKeyByExtension(".bin") } returns "stt_whisper"
        every { RemoteModelRegistry.getEngineKeyByExtension(".zip") } returns "wake_vosk"
        every { RemoteModelRegistry.isZipEngine(any()) } returns false
        every { RemoteModelRegistry.isLlmEngine(any()) } returns false

        mockkObject(PermissionUtils)
        every { PermissionUtils.canDrawOverlays(any()) } returns false
        every { PermissionUtils.hasMicrophonePermission(any()) } returns false
        every { PermissionUtils.hasNotificationPermission(any()) } returns false

        mockkObject(com.voxcommander.app.domain.search.LocationHelper)
        every { com.voxcommander.app.domain.search.LocationHelper.hasLocationPermission(any()) } returns false

        context = mockk(relaxed = true)
    }

    @Test
    fun `voiceModelReady is true for Google processor`() {
        val settings = TestDataFactory.createAppSettings(
            voiceProcessor = Strings.Processors.GOOGLE
        )
        val state = AppState.fromAppSettings(settings, context, emptyMap())
        assertTrue(state.voiceModelReady)
    }

    @Test
    fun `voiceModelReady is true for Whisper API processor`() {
        val settings = TestDataFactory.createAppSettings(
            voiceProcessor = Strings.Processors.WHISPER_API
        )
        val state = AppState.fromAppSettings(settings, context, emptyMap())
        assertTrue(state.voiceModelReady)
    }

    @Test
    fun `voiceModelReady is true for Whisper Vulkan when model is downloaded`() {
        val settings = TestDataFactory.createSettingsWithWhisperVulkan(
            activeVoiceModelId = "base",
            downloadedModelIds = setOf("base")
        )
        val state = AppState.fromAppSettings(settings, context, emptyMap())
        assertTrue(state.voiceModelReady)
    }

    @Test
    fun `voiceModelReady is false for Whisper Vulkan when model not downloaded`() {
        val settings = TestDataFactory.createSettingsWithWhisperVulkan(
            activeVoiceModelId = "base",
            downloadedModelIds = emptySet()
        )
        val state = AppState.fromAppSettings(settings, context, emptyMap())
        assertFalse(state.voiceModelReady)
    }

    @Test
    fun `voiceModelReady is true for Whisper Vulkan when custom path is set`() {
        val settings = TestDataFactory.createSettingsWithWhisperVulkan(
            activeVoiceModelId = null,
            downloadedModelIds = emptySet()
        ).copy(
            customModelPaths = mapOf("stt_whisper" to "/sdcard/model.bin")
        )
        val state = AppState.fromAppSettings(settings, context, emptyMap())
        assertTrue(state.voiceModelReady)
    }

    @Test
    fun `voiceModelReady is true for Vosk when model downloaded`() {
        every { RemoteModelRegistry.isZipEngine("wake_vosk") } returns true
        val settings = TestDataFactory.createAppSettings(
            voiceProcessor = "wake_vosk",
            activeVoiceModelId = "vosk-small",
            downloadedModelIds = setOf("vosk-small")
        )
        val state = AppState.fromAppSettings(settings, context, emptyMap())
        assertTrue(state.voiceModelReady)
    }

    @Test
    fun `voiceModelReady is false for Vosk when model not downloaded and no custom path`() {
        every { RemoteModelRegistry.isZipEngine("wake_vosk") } returns true
        val settings = TestDataFactory.createAppSettings(
            voiceProcessor = "wake_vosk",
            activeVoiceModelId = "vosk-small",
            downloadedModelIds = emptySet()
        )
        val state = AppState.fromAppSettings(settings, context, emptyMap())
        assertFalse(state.voiceModelReady)
    }

    @Test
    fun `intentModelReady is true for OpenAI processor`() {
        val settings = TestDataFactory.createAppSettings(
            aiProcessor = Strings.AiProcessors.OPENAI
        )
        val state = AppState.fromAppSettings(settings, context, emptyMap())
        assertTrue(state.intentModelReady)
    }

    @Test
    fun `intentModelReady is true for GeminiNative when not incompatible`() {
        val settings = TestDataFactory.createSettingsWithGeminiNative(
            geminiIncompatible = false
        )
        val state = AppState.fromAppSettings(settings, context, emptyMap())
        assertTrue(state.intentModelReady)
    }

    @Test
    fun `intentModelReady is false for GeminiNative when incompatible`() {
        val settings = TestDataFactory.createSettingsWithGeminiNative(
            geminiIncompatible = true
        )
        val state = AppState.fromAppSettings(settings, context, emptyMap())
        assertFalse(state.intentModelReady)
    }

    @Test
    fun `intentModelReady is true for GeminiCloud when API key is set`() {
        val settings = TestDataFactory.createSettingsWithGeminiCloud(
            geminiApiKey = "test-key"
        )
        val state = AppState.fromAppSettings(settings, context, emptyMap())
        assertTrue(state.intentModelReady)
    }

    @Test
    fun `intentModelReady is false for GeminiCloud when API key is null`() {
        val settings = TestDataFactory.createSettingsWithGeminiCloud(
            geminiApiKey = null
        )
        val state = AppState.fromAppSettings(settings, context, emptyMap())
        assertFalse(state.intentModelReady)
    }

    @Test
    fun `intentModelReady is true for LLM engine when model is downloaded`() {
        every { RemoteModelRegistry.isLlmEngine("nlu_llm") } returns true
        val settings = TestDataFactory.createSettingsWithLlmEngine(
            activeIntentModelId = "qwen2.5-1.5b-q8",
            downloadedModelIds = setOf("qwen2.5-1.5b-q8")
        )
        val state = AppState.fromAppSettings(settings, context, emptyMap())
        assertTrue(state.intentModelReady)
    }

    @Test
    fun `intentModelReady is false for LLM engine when model not downloaded`() {
        every { RemoteModelRegistry.isLlmEngine("nlu_llm") } returns true
        val settings = TestDataFactory.createSettingsWithLlmEngine(
            activeIntentModelId = "qwen2.5-1.5b-q8",
            downloadedModelIds = emptySet()
        )
        val state = AppState.fromAppSettings(settings, context, emptyMap())
        assertFalse(state.intentModelReady)
    }

    @Test
    fun `intentModelReady is false for unknown processor`() {
        val settings = TestDataFactory.createAppSettings(
            aiProcessor = "unknown_processor"
        )
        val state = AppState.fromAppSettings(settings, context, emptyMap())
        assertFalse(state.intentModelReady)
    }

    @Test
    fun `fromAppSettings maps voiceProcessor and voiceLanguage`() {
        val settings = TestDataFactory.createAppSettings(
            voiceProcessor = Strings.Processors.WHISPER_VULKAN,
            voiceLanguage = "ro"
        )
        val state = AppState.fromAppSettings(settings, context, emptyMap())
        assertEquals(Strings.Processors.WHISPER_VULKAN, state.voiceProcessor)
        assertEquals("ro", state.voiceLanguage)
    }

    @Test
    fun `fromAppSettings maps aiProcessor and cloudIntelligenceEnabled`() {
        val settings = TestDataFactory.createAppSettings(
            aiProcessor = Strings.AiProcessors.GEMINI_CLOUD,
            cloudIntelligenceEnabled = false
        )
        val state = AppState.fromAppSettings(settings, context, emptyMap())
        assertEquals(Strings.AiProcessors.GEMINI_CLOUD, state.aiProcessor)
        assertFalse(state.cloudIntelligenceEnabled)
    }

    @Test
    fun `fromAppSettings maps downloadedModelIds`() {
        val settings = TestDataFactory.createAppSettings(
            downloadedModelIds = setOf("base", "tiny", "qwen")
        )
        val state = AppState.fromAppSettings(settings, context, emptyMap())
        assertEquals(setOf("base", "tiny", "qwen"), state.downloadedModelIds)
        assertTrue(state.isModelDownloaded("base"))
        assertFalse(state.isModelDownloaded("nonexistent"))
    }

    @Test
    fun `fromAppSettings maps availableModels`() {
        val models = mapOf(
            "stt_whisper" to listOf(
                TestDataFactory.createRemoteModelItem(id = "base"),
                TestDataFactory.createRemoteModelItem(id = "tiny")
            )
        )
        val settings = TestDataFactory.createAppSettings()
        val state = AppState.fromAppSettings(settings, context, models)
        assertEquals(2, state.availableModels["stt_whisper"]?.size)
    }

    @Test
    fun `fromAppSettings maps fallback settings`() {
        val settings = TestDataFactory.createAppSettings(
            defaultVoiceFallbackProcessor = "stt_whisper",
            defaultVoiceFallbackModel = "tiny",
            defaultIntentFallbackProcessor = "nlu_llm",
            defaultIntentFallbackModel = "qwen"
        )
        val state = AppState.fromAppSettings(settings, context, emptyMap())
        assertEquals("stt_whisper", state.defaultVoiceFallbackProcessor)
        assertEquals("tiny", state.defaultVoiceFallbackModel)
        assertEquals("nlu_llm", state.defaultIntentFallbackProcessor)
        assertEquals("qwen", state.defaultIntentFallbackModel)
    }

    @Test
    fun `fromAppSettings maps wake word settings`() {
        val settings = TestDataFactory.createAppSettings(
            wakeWordModelPath = "/path/to/model",
            wakeWordEngineType = "porcupine"
        ).copy(
            wakeWord = "hi vox",
            wakeWordEnabled = true
        )
        val state = AppState.fromAppSettings(settings, context, emptyMap())
        assertEquals("hi vox", state.wakeWord)
        assertTrue(state.wakeWordEnabled)
        assertEquals("/path/to/model", state.wakeWordModelPath)
        assertEquals("porcupine", state.wakeWordEngineType)
    }

    @Test
    fun `initial returns correct defaults`() {
        val state = AppState.initial()
        assertEquals("", state.voiceProcessor)
        assertEquals("", state.aiProcessor)
        assertFalse(state.voiceModelReady)
        assertFalse(state.intentModelReady)
        assertEquals(VoiceState.IDLE, state.voiceState)
    }
}
