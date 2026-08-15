package com.voxapps.commander.domain.intent

import android.util.Log
import com.voxapps.commander.data.preferences.AppSettings
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.domain.engine.EngineSelection
import com.voxapps.commander.domain.intent.interpreter.AssistantEngine
import com.voxapps.commander.domain.intent.interpreter.SelectableModelEngine
import com.voxapps.commander.domain.intent.model.NluIntent
import com.voxapps.commander.domain.intent.taxonomy.IntentTaxonomy
import com.voxapps.commander.testutil.TestDataFactory
import com.voxapps.commander.utils.Strings
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import com.voxapps.commander.domain.intent.interpreter.LocalLlmEngine

@OptIn(ExperimentalCoroutinesApi::class)
class IntentDecisionMapTest {

    private lateinit var l1Engine: AssistantEngine
    private lateinit var l2CloudEngine: AssistantEngine
    private lateinit var l3LocalEngine: LocalLlmEngine
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
        settingsRepo = mockk(relaxed = true)

        decisionMap = IntentDecisionMap(
            l1Engine,
            l2CloudEngine,
            listOf(l3LocalEngine),
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
        coEvery { l3LocalEngine.processCommand(command, any(), any()) } returns expectedIntent

        val settingsWithFallback = AppSettings(
            cloudIntelligenceEnabled = true,
            aiProcessor = Strings.AiProcessors.OPENAI,
            defaultIntentFallbackProcessor = "nlu_llm",
            defaultIntentFallbackModel = "qwen2.5-1.5b-q8"
        )
        every { settingsRepo.getSettingsSnapshot() } returns settingsWithFallback

        // Mock RemoteModelRegistry.isLlmEngine
        mockkObject(com.voxapps.commander.data.remote.RemoteModelRegistry)
        every { com.voxapps.commander.data.remote.RemoteModelRegistry.isLlmEngine("nlu_llm") } returns true
        every { com.voxapps.commander.data.remote.RemoteModelRegistry.backendOf(any()) } returns null

        val result = decisionMap.processCommand(command, null)

        assertNotNull(result)
        assertEquals(expectedIntent.domain, result?.domain)
        coVerify { l2CloudEngine.processCommand(command, any()) }
        coVerify { l3LocalEngine.processCommand(command, any(), any()) }
    }

    @Test
    fun `when all engines fail, should return null`() = runTest {
        coEvery { l1Engine.processCommand(any(), any()) } returns null
        coEvery { l2CloudEngine.processCommand(any(), any()) } returns null
        coEvery { l3LocalEngine.processCommand(any(), any()) } returns null

        val result = decisionMap.processCommand("bla bla", null)

        assertNull(result)
    }

    @Test
    fun `when cloud intelligence is disabled, should skip L2 Cloud and use fallback`() = runTest {
        val command = "play music"
        val expectedIntent = TestDataFactory.createPlayMusicIntent()

        coEvery { l1Engine.processCommand(command, any()) } returns null
        coEvery { l3LocalEngine.processCommand(command, any(), any()) } returns expectedIntent

        val settingsCloudDisabled = TestDataFactory.createAppSettings(
            cloudIntelligenceEnabled = false,
            aiProcessor = Strings.AiProcessors.OPENAI,
            defaultIntentFallbackProcessor = "nlu_llm",
            defaultIntentFallbackModel = "qwen2.5-1.5b-q8"
        )
        every { settingsRepo.getSettingsSnapshot() } returns settingsCloudDisabled

        mockkObject(com.voxapps.commander.data.remote.RemoteModelRegistry)
        every { com.voxapps.commander.data.remote.RemoteModelRegistry.isLlmEngine("nlu_llm") } returns true
        every { com.voxapps.commander.data.remote.RemoteModelRegistry.backendOf(any()) } returns null

        val result = decisionMap.processCommand(command, null)

        assertNotNull(result)
        coVerify(exactly = 0) { l2CloudEngine.processCommand(any(), any()) }
        coVerify { l3LocalEngine.processCommand(command, any(), any()) }
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
    fun `when primary is LLM engine, should call l3LocalEngine in L2`() = runTest {
        val command = "play music"
        val expectedIntent = TestDataFactory.createPlayMusicIntent()

        coEvery { l1Engine.processCommand(command, any()) } returns null
        coEvery { l3LocalEngine.processCommand(command, any(), any()) } returns expectedIntent

        val settings = TestDataFactory.createSettingsWithLlmEngine(
            downloadedModelIds = setOf("qwen2.5-1.5b-q8")
        )
        every { settingsRepo.getSettingsSnapshot() } returns settings

        mockkObject(com.voxapps.commander.data.remote.RemoteModelRegistry)
        every { com.voxapps.commander.data.remote.RemoteModelRegistry.isLlmEngine("nlu_llm") } returns true
        every { com.voxapps.commander.data.remote.RemoteModelRegistry.backendOf(any()) } returns null

        val result = decisionMap.processCommand(command, null)

        assertNotNull(result)
        coVerify { l3LocalEngine.processCommand(command, any(), any()) }
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
        coEvery { l3LocalEngine.processCommand(command, any(), any()) } returns expectedIntent

        val settings = TestDataFactory.createAppSettings(
            cloudIntelligenceEnabled = true,
            aiProcessor = Strings.AiProcessors.OPENAI,
            defaultIntentFallbackProcessor = "nlu_llm",
            defaultIntentFallbackModel = "qwen2.5-1.5b-q8"
        )
        every { settingsRepo.getSettingsSnapshot() } returns settings

        mockkObject(com.voxapps.commander.data.remote.RemoteModelRegistry)
        every { com.voxapps.commander.data.remote.RemoteModelRegistry.isLlmEngine("nlu_llm") } returns true
        every { com.voxapps.commander.data.remote.RemoteModelRegistry.backendOf(any()) } returns null

        val result = decisionMap.processCommand(command, null)

        assertNotNull(result)
        assertEquals(expectedIntent.domain, result?.domain)
        coVerify { l3LocalEngine.processCommand(command, any(), any()) }
    }

    /**
     * Every on-device processor key resolves to the same LocalLlmInterpreter instance, so a fallback
     * naming the primary's own selection has nothing to escalate to: it would re-run the inference
     * that just failed, on the model already in memory, for the price of a second full timeout.
     * Settings can arrive in that shape from an imported backup whatever the picker allows.
     */
    @Test
    fun `an on-device fallback on the primary's own selection is not run twice`() = runTest {
        val command = "play music"

        coEvery { l1Engine.processCommand(command, any()) } returns null
        coEvery { l3LocalEngine.processCommand(command, any(), any()) } returns null

        mockkObject(com.voxapps.commander.data.remote.RemoteModelRegistry)
        every { com.voxapps.commander.data.remote.RemoteModelRegistry.isLlmEngine("nlu_llm") } returns true
        every { com.voxapps.commander.data.remote.RemoteModelRegistry.backendOf(any()) } returns null

        every { settingsRepo.getSettingsSnapshot() } returns TestDataFactory.createAppSettings(
            cloudIntelligenceEnabled = true,
            aiProcessor = "nlu_llm",
            activeIntentModelId = "qwen2.5-1.5b-q8",
            defaultIntentFallbackProcessor = "nlu_llm",
            defaultIntentFallbackModel = "qwen2.5-1.5b-q8"
        )

        val result = decisionMap.processCommand(command, null)

        assertNull(result)
        coVerify(exactly = 1) { l3LocalEngine.processCommand(command, any(), any()) }
    }

    /**
     * The other half of the same rule. A *different* on-device model is a real fallback — a primary
     * that fails to load is exactly when the smaller model earns its place — so it must not be
     * skipped just because both selections reach the same interpreter instance.
     */
    @Test
    fun `an on-device fallback on a different model is run`() = runTest {
        val command = "play music"
        val expectedIntent = TestDataFactory.createPlayMusicIntent()

        coEvery { l1Engine.processCommand(command, any()) } returns null
        coEvery {
            l3LocalEngine.processCommand(command, any(), EngineSelection("nlu_llm", "qwen2.5-1.5b-q8"))
        } returns null
        coEvery {
            l3LocalEngine.processCommand(command, any(), EngineSelection("nlu_llm", "qwen2.5-0.5b-q8"))
        } returns expectedIntent

        mockkObject(com.voxapps.commander.data.remote.RemoteModelRegistry)
        every { com.voxapps.commander.data.remote.RemoteModelRegistry.isLlmEngine("nlu_llm") } returns true
        every { com.voxapps.commander.data.remote.RemoteModelRegistry.backendOf(any()) } returns null

        every { settingsRepo.getSettingsSnapshot() } returns TestDataFactory.createAppSettings(
            aiProcessor = "nlu_llm",
            activeIntentModelId = "qwen2.5-1.5b-q8",
            defaultIntentFallbackProcessor = "nlu_llm",
            defaultIntentFallbackModel = "qwen2.5-0.5b-q8"
        )

        val result = decisionMap.processCommand(command, null)

        assertEquals(expectedIntent.domain, result?.domain)
    }

    /**
     * The defect that made the whole feature inert: the fallback stage ran, but the interpreter
     * loaded `activeIntentModelId` — the *primary's* model — so it re-ran the failed inference and
     * the user's chosen fallback model was never loaded by anything.
     */
    @Test
    fun `L3 runs the fallback's own model, not the active one`() = runTest {
        val command = "play music"
        val expectedIntent = TestDataFactory.createPlayMusicIntent()

        coEvery { l1Engine.processCommand(command, any()) } returns null
        coEvery { l2CloudEngine.processCommand(command, any()) } returns null
        coEvery { l3LocalEngine.processCommand(command, any(), any()) } returns expectedIntent

        mockkObject(com.voxapps.commander.data.remote.RemoteModelRegistry)
        every { com.voxapps.commander.data.remote.RemoteModelRegistry.isLlmEngine("nlu_llm") } returns true
        every { com.voxapps.commander.data.remote.RemoteModelRegistry.backendOf(any()) } returns null

        every { settingsRepo.getSettingsSnapshot() } returns TestDataFactory.createAppSettings(
            aiProcessor = Strings.AiProcessors.OPENAI,
            activeIntentModelId = "gpt-4o-mini",
            defaultIntentFallbackProcessor = "nlu_llm",
            defaultIntentFallbackModel = "qwen2.5-0.5b-q8"
        )

        val result = decisionMap.processCommand(command, null)

        assertNotNull(result)
        coVerify {
            l3LocalEngine.processCommand(command, any(), EngineSelection("nlu_llm", "qwen2.5-0.5b-q8"))
        }
    }
}
