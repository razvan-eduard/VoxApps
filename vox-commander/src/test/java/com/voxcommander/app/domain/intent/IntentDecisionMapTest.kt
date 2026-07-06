package com.voxcommander.app.domain.intent

import android.util.Log
import com.voxcommander.app.data.preferences.AppSettings
import com.voxcommander.app.data.preferences.SettingsRepository
import com.voxcommander.app.domain.intent.interpreter.AssistantEngine
import com.voxcommander.app.domain.intent.model.NluIntent
import com.voxcommander.app.domain.intent.taxonomy.IntentTaxonomy
import com.voxcommander.app.testutil.TestDataFactory
import com.voxcommander.app.utils.Strings
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IntentDecisionMapTest {

    private lateinit var l1Engine: AssistantEngine
    private lateinit var l2CloudEngine: AssistantEngine
    private lateinit var l3LocalEngine: AssistantEngine
    private lateinit var geminiNanoEngine: AssistantEngine
    private lateinit var geminiCloudEngine: AssistantEngine
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var decisionMap: IntentDecisionMap

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0

        l1Engine = mockk()
        l2CloudEngine = mockk()
        l3LocalEngine = mockk()
        geminiNanoEngine = mockk()
        geminiCloudEngine = mockk()
        settingsRepo = mockk(relaxed = true)

        decisionMap = IntentDecisionMap(
            l1Engine,
            l2CloudEngine,
            l3LocalEngine,
            geminiNanoEngine,
            geminiCloudEngine,
            settingsRepo
        )

        // Mock default settings snapshot
        val defaultSettings = AppSettings(
            cloudIntelligenceEnabled = true,
            aiProcessor = Strings.AiProcessors.OPENAI
        )
        every { settingsRepo.getSettingsSnapshot() } returns defaultSettings
    }

    @Test
    fun `when L1 engine matches, should return result immediately without calling other engines`() = runTest {
        val command = "pune muzica"
        val expectedIntent = TestDataFactory.createPlayMusicIntent()

        coEvery { l1Engine.processCommand(command, any()) } returns expectedIntent

        val result = decisionMap.processCommand(command, null)

        assertNotNull(result)
        assertEquals(expectedIntent.domain, result?.domain)
        assertEquals(expectedIntent.action, result?.action)
        coVerify(exactly = 1) { l1Engine.processCommand(command, any()) }
        coVerify(exactly = 0) { l2CloudEngine.processCommand(any(), any()) }
        coVerify(exactly = 0) { l3LocalEngine.processCommand(any(), any()) }
    }

    @Test
    fun `when L1 misses and primary is OpenAI, should call L2 Cloud engine`() = runTest {
        val command = "vreau la brasov"
        val expectedIntent = TestDataFactory.createNavigateIntent(destination = "brasov")

        coEvery { l1Engine.processCommand(command, any()) } returns null
        coEvery { l2CloudEngine.processCommand(command, any()) } returns expectedIntent

        val result = decisionMap.processCommand(command, null)

        assertNotNull(result)
        assertEquals(expectedIntent.domain, result?.domain)
        coVerify { l1Engine.processCommand(command, any()) }
        coVerify { l2CloudEngine.processCommand(command, any()) }
    }

    @Test
    fun `when L2 Cloud fails and fallback is LLM, should call L3 fallback`() = runTest {
        val command = "cat e ceasul"
        val expectedIntent = TestDataFactory.createNluIntent(
            domain = IntentTaxonomy.Domains.SYSTEM,
            action = IntentTaxonomy.Actions.VOLUME_UP
        )

        coEvery { l1Engine.processCommand(command, any()) } returns null
        coEvery { l2CloudEngine.processCommand(command, any()) } returns null
        coEvery { l3LocalEngine.processCommand(command, any()) } returns expectedIntent

        val settingsWithFallback = AppSettings(
            cloudIntelligenceEnabled = true,
            aiProcessor = Strings.AiProcessors.OPENAI,
            defaultIntentFallbackProcessor = "nlu_llm",
            defaultIntentFallbackModel = "qwen2.5-1.5b-q8"
        )
        every { settingsRepo.getSettingsSnapshot() } returns settingsWithFallback

        // Mock RemoteModelRegistry.isLlmEngine
        mockkObject(com.voxcommander.app.data.remote.RemoteModelRegistry)
        every { com.voxcommander.app.data.remote.RemoteModelRegistry.isLlmEngine("nlu_llm") } returns true

        val result = decisionMap.processCommand(command, null)

        assertNotNull(result)
        assertEquals(expectedIntent.domain, result?.domain)
        coVerify { l2CloudEngine.processCommand(command, any()) }
        coVerify { l3LocalEngine.processCommand(command, any()) }
    }

    @Test
    fun `when all engines fail, should return null`() = runTest {
        coEvery { l1Engine.processCommand(any(), any()) } returns null
        coEvery { l2CloudEngine.processCommand(any(), any()) } returns null
        coEvery { l3LocalEngine.processCommand(any(), any()) } returns null
        coEvery { geminiNanoEngine.processCommand(any(), any()) } returns null
        coEvery { geminiCloudEngine.processCommand(any(), any()) } returns null

        val result = decisionMap.processCommand("bla bla", null)

        assertNull(result)
    }

    @Test
    fun `when primary is GeminiNative, should call geminiNanoEngine`() = runTest {
        val command = "play music"
        val expectedIntent = TestDataFactory.createPlayMusicIntent()

        coEvery { l1Engine.processCommand(command, any()) } returns null
        coEvery { geminiNanoEngine.processCommand(command, any()) } returns expectedIntent

        val settingsWithGemini = AppSettings(
            cloudIntelligenceEnabled = true,
            aiProcessor = Strings.AiProcessors.GEMINI_NATIVE
        )
        every { settingsRepo.getSettingsSnapshot() } returns settingsWithGemini

        val result = decisionMap.processCommand(command, null)

        assertNotNull(result)
        coVerify { geminiNanoEngine.processCommand(command, any()) }
        coVerify(exactly = 0) { l2CloudEngine.processCommand(any(), any()) }
    }

    @Test
    fun `when cloud intelligence is disabled, should skip L2 Cloud and use fallback`() = runTest {
        val command = "play music"
        val expectedIntent = TestDataFactory.createPlayMusicIntent()

        coEvery { l1Engine.processCommand(command, any()) } returns null
        coEvery { l3LocalEngine.processCommand(command, any()) } returns expectedIntent

        val settingsCloudDisabled = TestDataFactory.createAppSettings(
            cloudIntelligenceEnabled = false,
            aiProcessor = Strings.AiProcessors.OPENAI,
            defaultIntentFallbackProcessor = "nlu_llm",
            defaultIntentFallbackModel = "qwen2.5-1.5b-q8"
        )
        every { settingsRepo.getSettingsSnapshot() } returns settingsCloudDisabled

        mockkObject(com.voxcommander.app.data.remote.RemoteModelRegistry)
        every { com.voxcommander.app.data.remote.RemoteModelRegistry.isLlmEngine("nlu_llm") } returns true

        val result = decisionMap.processCommand(command, null)

        assertNotNull(result)
        coVerify(exactly = 0) { l2CloudEngine.processCommand(any(), any()) }
        coVerify { l3LocalEngine.processCommand(command, any()) }
    }

    @Test
    fun `when L3 fallback is same as primary, should skip redundant check`() = runTest {
        val command = "play music"

        coEvery { l1Engine.processCommand(command, any()) } returns null
        coEvery { l2CloudEngine.processCommand(command, any()) } returns null

        val settingsSameFallback = TestDataFactory.createAppSettings(
            cloudIntelligenceEnabled = true,
            aiProcessor = Strings.AiProcessors.OPENAI,
            defaultIntentFallbackProcessor = Strings.AiProcessors.OPENAI,
            defaultIntentFallbackModel = "gpt-4"
        )
        every { settingsRepo.getSettingsSnapshot() } returns settingsSameFallback

        val result = decisionMap.processCommand(command, null)

        assertNull(result)
        // L2 was called once (as primary), L3 should NOT be called again
        coVerify(exactly = 1) { l2CloudEngine.processCommand(command, any()) }
    }

    @Test
    fun `when primary is GEMINI_CLOUD and cloud enabled, should call geminiCloudEngine`() = runTest {
        val command = "play music"
        val expectedIntent = TestDataFactory.createPlayMusicIntent()

        coEvery { l1Engine.processCommand(command, any()) } returns null
        coEvery { geminiCloudEngine.processCommand(command, any()) } returns expectedIntent

        val settings = TestDataFactory.createSettingsWithGeminiCloud()
        every { settingsRepo.getSettingsSnapshot() } returns settings

        val result = decisionMap.processCommand(command, null)

        assertNotNull(result)
        coVerify { geminiCloudEngine.processCommand(command, any()) }
        coVerify(exactly = 0) { l2CloudEngine.processCommand(any(), any()) }
    }

    @Test
    fun `when primary is GEMINI_CLOUD and cloud disabled, should skip and use fallback`() = runTest {
        val command = "play music"
        val expectedIntent = TestDataFactory.createPlayMusicIntent()

        coEvery { l1Engine.processCommand(command, any()) } returns null
        coEvery { l3LocalEngine.processCommand(command, any()) } returns expectedIntent

        val settings = TestDataFactory.createAppSettings(
            aiProcessor = Strings.AiProcessors.GEMINI_CLOUD,
            cloudIntelligenceEnabled = false,
            defaultIntentFallbackProcessor = "nlu_llm",
            defaultIntentFallbackModel = "qwen2.5-1.5b-q8"
        )
        every { settingsRepo.getSettingsSnapshot() } returns settings

        mockkObject(com.voxcommander.app.data.remote.RemoteModelRegistry)
        every { com.voxcommander.app.data.remote.RemoteModelRegistry.isLlmEngine("nlu_llm") } returns true

        val result = decisionMap.processCommand(command, null)

        assertNotNull(result)
        coVerify(exactly = 0) { geminiCloudEngine.processCommand(any(), any()) }
        coVerify { l3LocalEngine.processCommand(command, any()) }
    }

    @Test
    fun `when L2 fails and fallback is GEMINI_CLOUD, should call geminiCloudEngine in L3`() = runTest {
        val command = "play music"
        val expectedIntent = TestDataFactory.createPlayMusicIntent()

        coEvery { l1Engine.processCommand(command, any()) } returns null
        coEvery { l2CloudEngine.processCommand(command, any()) } returns null
        coEvery { geminiCloudEngine.processCommand(command, any()) } returns expectedIntent

        val settings = TestDataFactory.createAppSettings(
            cloudIntelligenceEnabled = true,
            aiProcessor = Strings.AiProcessors.OPENAI,
            defaultIntentFallbackProcessor = Strings.AiProcessors.GEMINI_CLOUD,
            defaultIntentFallbackModel = "gemini-pro"
        )
        every { settingsRepo.getSettingsSnapshot() } returns settings

        val result = decisionMap.processCommand(command, null)

        assertNotNull(result)
        coVerify { l2CloudEngine.processCommand(command, any()) }
        coVerify { geminiCloudEngine.processCommand(command, any()) }
    }

    @Test
    fun `when L2 fails and fallback is GEMINI_NATIVE, should call geminiNanoEngine in L3`() = runTest {
        val command = "play music"
        val expectedIntent = TestDataFactory.createPlayMusicIntent()

        coEvery { l1Engine.processCommand(command, any()) } returns null
        coEvery { l2CloudEngine.processCommand(command, any()) } returns null
        coEvery { geminiNanoEngine.processCommand(command, any()) } returns expectedIntent

        val settings = TestDataFactory.createAppSettings(
            cloudIntelligenceEnabled = true,
            aiProcessor = Strings.AiProcessors.OPENAI,
            defaultIntentFallbackProcessor = Strings.AiProcessors.GEMINI_NATIVE,
            defaultIntentFallbackModel = "gemini-nano"
        )
        every { settingsRepo.getSettingsSnapshot() } returns settings

        val result = decisionMap.processCommand(command, null)

        assertNotNull(result)
        coVerify { geminiNanoEngine.processCommand(command, any()) }
    }

    @Test
    fun `when primary is LLM engine, should call l3LocalEngine in L2`() = runTest {
        val command = "play music"
        val expectedIntent = TestDataFactory.createPlayMusicIntent()

        coEvery { l1Engine.processCommand(command, any()) } returns null
        coEvery { l3LocalEngine.processCommand(command, any()) } returns expectedIntent

        val settings = TestDataFactory.createSettingsWithLlmEngine(
            downloadedModelIds = setOf("qwen2.5-1.5b-q8")
        )
        every { settingsRepo.getSettingsSnapshot() } returns settings

        mockkObject(com.voxcommander.app.data.remote.RemoteModelRegistry)
        every { com.voxcommander.app.data.remote.RemoteModelRegistry.isLlmEngine("nlu_llm") } returns true

        val result = decisionMap.processCommand(command, null)

        assertNotNull(result)
        coVerify { l3LocalEngine.processCommand(command, any()) }
        coVerify(exactly = 0) { l2CloudEngine.processCommand(any(), any()) }
    }

    @Test
    fun `when blank command is passed, should return null without calling any engine`() = runTest {
        val result = decisionMap.processCommand("", null)

        assertNull(result)
        coVerify(exactly = 0) { l1Engine.processCommand(any(), any()) }
        coVerify(exactly = 0) { l2CloudEngine.processCommand(any(), any()) }
    }

    @Test
    fun `when L2 engine throws exception, should fall through to L3`() = runTest {
        val command = "play music"
        val expectedIntent = TestDataFactory.createPlayMusicIntent()

        coEvery { l1Engine.processCommand(command, any()) } returns null
        coEvery { l2CloudEngine.processCommand(command, any()) } throws RuntimeException("API error")
        coEvery { l3LocalEngine.processCommand(command, any()) } returns expectedIntent

        val settings = TestDataFactory.createAppSettings(
            cloudIntelligenceEnabled = true,
            aiProcessor = Strings.AiProcessors.OPENAI,
            defaultIntentFallbackProcessor = "nlu_llm",
            defaultIntentFallbackModel = "qwen2.5-1.5b-q8"
        )
        every { settingsRepo.getSettingsSnapshot() } returns settings

        mockkObject(com.voxcommander.app.data.remote.RemoteModelRegistry)
        every { com.voxcommander.app.data.remote.RemoteModelRegistry.isLlmEngine("nlu_llm") } returns true

        val result = decisionMap.processCommand(command, null)

        assertNotNull(result)
        assertEquals(expectedIntent.domain, result?.domain)
        coVerify { l3LocalEngine.processCommand(command, any()) }
    }
}
