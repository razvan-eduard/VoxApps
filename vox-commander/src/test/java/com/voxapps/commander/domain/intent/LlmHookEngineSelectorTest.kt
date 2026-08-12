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
    private lateinit var localLlmEngine: AssistantEngine
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var selector: LlmHookEngineSelector

    @Before
    fun setup() {
        openAiEngine = mockk()
        localLlmEngine = mockk()
        settingsRepo = mockk()
        selector = LlmHookEngineSelector(openAiEngine, localLlmEngine, settingsRepo)
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
        // openAiEngine here is a bare AssistantEngine mock, not a real OpenAiInterpreter, so the
        // selector's cast for lastErrorReason can't find a specific cause — "unknown error" is the
        // correct fallback in that case. The specific-cause path (401 -> "invalid or revoked", a 5xx
        // -> "server error", etc.) lives on OpenAiInterpreter itself, exercised by that class's own
        // tests instead of through this generic-mock selector test.
        every { settingsRepo.getSettingsSnapshot() } returns settingsWith(Strings.AiProcessors.OPENAI)
        coEvery { openAiEngine.rawPrompt(any()) } returns null

        val outcome = selector.run("hi")

        assertTrue(outcome is RawPromptOutcome.Error)
        assertEquals("OpenAI request failed: unknown error", (outcome as RawPromptOutcome.Error).reason)
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
        // localLlmEngine here is a bare AssistantEngine mock, not a real LocalLlmInterpreter, so
        // the selector's cast for lastErrorReason can't find a specific cause — the generic
        // unavailable fallback is correct here. The specific causes (busy/timeout, model missing,
        // generation failure) live on LocalLlmInterpreter, exercised by that class's own tests.
        mockkObject(RemoteModelRegistry)
        every { RemoteModelRegistry.isLlmEngine("nlu_llm") } returns true
        every { settingsRepo.getSettingsSnapshot() } returns settingsWith("nlu_llm")
        coEvery { localLlmEngine.rawPrompt(any()) } returns null

        val outcome = selector.run("hi")

        assertTrue(outcome is RawPromptOutcome.Error)
        assertEquals(
            "Local engine: unavailable (not downloaded or failed to load)",
            (outcome as RawPromptOutcome.Error).reason
        )
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
