package com.voxapps.commander.domain.intent

import com.voxapps.commander.data.preferences.AppSettings
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.data.remote.RemoteModelRegistry
import com.voxapps.commander.domain.intent.interpreter.AssistantEngine
import com.voxapps.commander.utils.Strings
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LlmHookEngineSelectorTest {

    private lateinit var openAiEngine: AssistantEngine
    private lateinit var geminiCloudEngine: AssistantEngine
    private lateinit var localLlmEngine: AssistantEngine
    private lateinit var geminiNanoEngine: AssistantEngine
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var selector: LlmHookEngineSelector

    @Before
    fun setup() {
        openAiEngine = mockk()
        geminiCloudEngine = mockk()
        localLlmEngine = mockk()
        geminiNanoEngine = mockk()
        settingsRepo = mockk()
        selector = LlmHookEngineSelector(openAiEngine, geminiCloudEngine, localLlmEngine, geminiNanoEngine, settingsRepo)
    }

    private fun settingsWith(processor: String, cloudEnabled: Boolean = true) =
        AppSettings(aiProcessor = processor, cloudIntelligenceEnabled = cloudEnabled)

    @Test
    fun `routes to OpenAI when configured and cloud enabled`() = runTest {
        every { settingsRepo.getSettingsSnapshot() } returns settingsWith(Strings.AiProcessors.OPENAI)
        coEvery { openAiEngine.rawPrompt("hi") } returns "hello back"

        val outcome = selector.run("hi")

        assertTrue(outcome is RawPromptOutcome.Success)
        assertEquals("hello back", (outcome as RawPromptOutcome.Success).rawText)
    }

    @Test
    fun `OpenAI blocked when cloud intelligence disabled`() = runTest {
        every { settingsRepo.getSettingsSnapshot() } returns settingsWith(Strings.AiProcessors.OPENAI, cloudEnabled = false)

        val outcome = selector.run("hi")

        assertTrue(outcome is RawPromptOutcome.Error)
        assertEquals("Cloud intelligence disabled", (outcome as RawPromptOutcome.Error).reason)
    }

    @Test
    fun `OpenAI failure (null rawPrompt) produces a descriptive error`() = runTest {
        every { settingsRepo.getSettingsSnapshot() } returns settingsWith(Strings.AiProcessors.OPENAI)
        coEvery { openAiEngine.rawPrompt(any()) } returns null

        val outcome = selector.run("hi")

        assertTrue(outcome is RawPromptOutcome.Error)
        assertEquals("OpenAI request failed (check API key)", (outcome as RawPromptOutcome.Error).reason)
    }

    @Test
    fun `routes to Gemini Cloud when configured and cloud enabled`() = runTest {
        every { settingsRepo.getSettingsSnapshot() } returns settingsWith(Strings.AiProcessors.GEMINI_CLOUD)
        coEvery { geminiCloudEngine.rawPrompt("hi") } returns "cloud answer"

        val outcome = selector.run("hi")

        assertTrue(outcome is RawPromptOutcome.Success)
        assertEquals("cloud answer", (outcome as RawPromptOutcome.Success).rawText)
    }

    @Test
    fun `Gemini Nano always short-circuits with an informative error`() = runTest {
        every { settingsRepo.getSettingsSnapshot() } returns settingsWith(Strings.AiProcessors.GEMINI_NATIVE)

        val outcome = selector.run("hi")

        assertTrue(outcome is RawPromptOutcome.Error)
        assertEquals(
            "Gemini Nano on-device is not yet supported for generic LLM requests",
            (outcome as RawPromptOutcome.Error).reason
        )
    }

    @Test
    fun `routes to local LLM when aiProcessor is a JSON-defined llm engine key`() = runTest {
        mockkObject(RemoteModelRegistry)
        every { RemoteModelRegistry.isLlmEngine("nlu_llm") } returns true
        every { settingsRepo.getSettingsSnapshot() } returns settingsWith("nlu_llm")
        coEvery { localLlmEngine.rawPrompt("hi") } returns "local answer"

        val outcome = selector.run("hi")

        assertTrue(outcome is RawPromptOutcome.Success)
        assertEquals("local answer", (outcome as RawPromptOutcome.Success).rawText)
    }

    @Test
    fun `local LLM unavailable produces a descriptive error`() = runTest {
        mockkObject(RemoteModelRegistry)
        every { RemoteModelRegistry.isLlmEngine("nlu_llm") } returns true
        every { settingsRepo.getSettingsSnapshot() } returns settingsWith("nlu_llm")
        coEvery { localLlmEngine.rawPrompt(any()) } returns null

        val outcome = selector.run("hi")

        assertTrue(outcome is RawPromptOutcome.Error)
        assertEquals("Local model unavailable (not downloaded or failed to load)", (outcome as RawPromptOutcome.Error).reason)
    }

    @Test
    fun `unknown or unset processor produces a no-engine error`() = runTest {
        mockkObject(RemoteModelRegistry)
        every { RemoteModelRegistry.isLlmEngine("nothing_configured") } returns false
        every { settingsRepo.getSettingsSnapshot() } returns settingsWith("nothing_configured")

        val outcome = selector.run("hi")

        assertTrue(outcome is RawPromptOutcome.Error)
        assertEquals("No LLM engine configured", (outcome as RawPromptOutcome.Error).reason)
    }
}
