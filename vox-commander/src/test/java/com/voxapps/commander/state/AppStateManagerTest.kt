package com.voxapps.commander.state

import android.content.Context
import android.util.Log
import app.cash.turbine.test
import com.voxapps.commander.data.preferences.AppSettings
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.data.remote.RemoteModelRegistry
import com.voxapps.commander.utils.PermissionUtils
import com.voxapps.commander.utils.Strings
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppStateManagerTest {

    private lateinit var context: Context
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var stateManager: AppStateManager
    private lateinit var settingsFlow: MutableStateFlow<AppSettings>
    private lateinit var credentialsFlow: MutableStateFlow<com.voxapps.commander.data.preferences.Credentials>

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())

        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0

        mockkObject(PermissionUtils)
        every { PermissionUtils.canDrawOverlays(any()) } returns false
        every { PermissionUtils.hasMicrophonePermission(any()) } returns true
        every { PermissionUtils.hasNotificationPermission(any()) } returns true
        every { PermissionUtils.hasLocationPermission(any()) } returns false

        mockkObject(RemoteModelRegistry)
        every { RemoteModelRegistry.getEngineKeyByExtension(".bin") } returns "stt_whisper"
        every { RemoteModelRegistry.getEngineKeyByExtension(".zip") } returns "wake_vosk"
        every { RemoteModelRegistry.isZipEngine(any()) } returns false
        every { RemoteModelRegistry.isLlmEngine(any()) } returns false
        every { RemoteModelRegistry.getModels(any()) } returns emptyList()
        every { RemoteModelRegistry.modelMap } returns MutableStateFlow(emptyMap())
        every { RemoteModelRegistry.getExtension(any()) } returns ""

        context = mockk(relaxed = true)
        settingsRepo = mockk(relaxed = true)

        // Mock context properties used by refreshNativeLibsStatus()
        val appInfo = android.content.pm.ApplicationInfo()
        appInfo.nativeLibraryDir = ""
        every { context.applicationInfo } returns appInfo
        every { context.filesDir } returns java.io.File(System.getProperty("java.io.tmpdir"))
        every { context.getExternalFilesDir(any()) } returns java.io.File(System.getProperty("java.io.tmpdir"))

        // Mock Logger to avoid NPE from uninitialized context
        mockkObject(com.voxapps.logging.Logger)
        every { com.voxapps.logging.Logger.log(any(), any()) } returns Unit

        val initialSettings = AppSettings(
            voiceProcessor = Strings.Processors.GOOGLE,
            voiceLanguage = "en",
            aiProcessor = Strings.AiProcessors.OPENAI,
            cloudIntelligenceEnabled = true
        )
        settingsFlow = MutableStateFlow(initialSettings)
        every { settingsRepo.settingsFlow } returns settingsFlow
        // The encrypted store is a second source feeding uiState; without it the combine never
        // emits and every assertion below would read the initial state.
        credentialsFlow = MutableStateFlow(com.voxapps.commander.data.preferences.Credentials())
        every { settingsRepo.credentialsFlow } returns credentialsFlow
        every { settingsRepo.getSettingsSnapshot() } returns initialSettings

        // Reset singleton
        val field = AppStateManager::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, null)

        stateManager = AppStateManager.getInstance(settingsRepo, context)
    }

    @Test
    fun `when voice processor is updated, uiState emits new value`() = runTest {
        stateManager.uiState.test {
            val initial = awaitItem()
            assertEquals(Strings.Processors.GOOGLE, initial.voiceProcessor)

            // Update settings flow
            val newSettings = settingsFlow.value.copy(voiceProcessor = Strings.Processors.WHISPER_VULKAN, vulkanProbeDone = true)
            every { settingsRepo.getSettingsSnapshot() } returns newSettings
            settingsFlow.value = newSettings

            val updated = awaitItem()
            assertEquals(Strings.Processors.WHISPER_VULKAN, updated.voiceProcessor)
        }
    }

    @Test
    fun `when language is changed, uiState reflects it instantly`() = runTest {
        stateManager.uiState.test {
            val initial = awaitItem()
            assertEquals("en", initial.voiceLanguage)

            val newSettings = settingsFlow.value.copy(voiceLanguage = "ro")
            every { settingsRepo.getSettingsSnapshot() } returns newSettings
            settingsFlow.value = newSettings

            val updated = awaitItem()
            assertEquals("ro", updated.voiceLanguage)
        }
    }

    @Test
    fun `refreshAll reloads all state from SettingsRepository`() = runTest {
        stateManager.uiState.test {
            val initial = awaitItem()
            assertEquals(Strings.Processors.GOOGLE, initial.voiceProcessor)

            // Change settings
            val newSettings = settingsFlow.value.copy(voiceProcessor = Strings.Processors.WHISPER_VULKAN, voiceLanguage = "ro", vulkanProbeDone = true)
            every { settingsRepo.getSettingsSnapshot() } returns newSettings
            settingsFlow.value = newSettings

            stateManager.refreshAll()
            // refreshAll triggers a second emission via runtimeState update
            awaitItem() // consume first emission from settings flow change
            val updated = awaitItem() // consume second emission from refreshAll
            assertEquals(Strings.Processors.WHISPER_VULKAN, updated.voiceProcessor)
            assertEquals("ro", updated.voiceLanguage)
        }
    }

    @Test
    fun `intentModelReady is true for OpenAI processor`() = runTest {
        stateManager.uiState.test {
            val initial = awaitItem()
            assertTrue(initial.intentModelReady)
        }
    }

    @Test
    fun `intentModelReady is false for LLM engine when model not downloaded`() = runTest {
        stateManager.uiState.test {
            val initial = awaitItem()
            assertTrue(initial.intentModelReady) // OpenAI is always ready

            // Switch to LLM engine
            every { RemoteModelRegistry.isLlmEngine("nlu_llm") } returns true
            val newSettings = settingsFlow.value.copy(
                aiProcessor = "nlu_llm",
                activeIntentModelId = "qwen2.5-1.5b-q8",
                downloadedModelIds = emptySet()
            )
            every { settingsRepo.getSettingsSnapshot() } returns newSettings
            settingsFlow.value = newSettings

            val updated = awaitItem()
            assertFalse(updated.intentModelReady)
        }
    }

    @Test
    fun `voiceModelReady is true for Google processor`() = runTest {
        stateManager.uiState.test {
            val initial = awaitItem()
            assertTrue(initial.voiceModelReady)
        }
    }

    @Test
    fun `voiceModelReady is false for Whisper when model not downloaded`() = runTest {
        stateManager.uiState.test {
            val initial = awaitItem()
            assertTrue(initial.voiceModelReady) // Google is always ready

            val newSettings = settingsFlow.value.copy(
                voiceProcessor = Strings.Processors.WHISPER_VULKAN,
                activeVoiceModelId = "base",
                downloadedModelIds = emptySet(),
                vulkanProbeDone = true
            )
            every { settingsRepo.getSettingsSnapshot() } returns newSettings
            settingsFlow.value = newSettings

            val updated = awaitItem()
            assertFalse(updated.voiceModelReady)
        }
    }

    @Test
    fun `voiceModelReady is true for Whisper when model is downloaded`() = runTest {
        stateManager.uiState.test {
            val initial = awaitItem()
            assertTrue(initial.voiceModelReady) // Google is always ready

            val newSettings = settingsFlow.value.copy(
                voiceProcessor = Strings.Processors.WHISPER_VULKAN,
                activeVoiceModelId = "base",
                downloadedModelIds = setOf("base"),
                vulkanProbeDone = true
            )
            every { settingsRepo.getSettingsSnapshot() } returns newSettings
            settingsFlow.value = newSettings

            val updated = awaitItem()
            assertTrue(updated.voiceModelReady)
        }
    }

    @Test
    fun `multiple state updates are atomic`() = runTest {
        stateManager.uiState.test {
            val initial = awaitItem()

            // Perform multiple updates via settings flow
            val newSettings = settingsFlow.value.copy(
                voiceProcessor = Strings.Processors.WHISPER_VULKAN,
                voiceLanguage = "ro",
                cloudIntelligenceEnabled = false,
                vulkanProbeDone = true
            )
            every { settingsRepo.getSettingsSnapshot() } returns newSettings
            settingsFlow.value = newSettings

            val updated = awaitItem()
            assertEquals(Strings.Processors.WHISPER_VULKAN, updated.voiceProcessor)
            assertEquals("ro", updated.voiceLanguage)
            assertFalse(updated.cloudIntelligenceEnabled)
        }
    }

    @Test
    fun `setVoiceState updates runtime state`() = runTest {
        stateManager.uiState.test {
            val initial = awaitItem()
            assertEquals(VoiceState.IDLE, initial.voiceState)

            stateManager.setVoiceState(VoiceState.LISTENING_COMMAND)
            val updated = awaitItem()
            assertEquals(VoiceState.LISTENING_COMMAND, updated.voiceState)
        }
    }

    @Test
    fun `onWakeWordDetected emits wake word event`() = runTest {
        stateManager.wakeWordEvents.test {
            stateManager.onWakeWordDetected()
            awaitItem()
            // SharedFlow with extraBufferCapacity=1 — event is emitted once
        }
    }

    @Test
    fun `onWakeWordDetected can be called multiple times`() = runTest {
        stateManager.wakeWordEvents.test {
            stateManager.onWakeWordDetected()
            awaitItem()
            stateManager.onWakeWordDetected()
            awaitItem()
        }
    }

    @Test
    fun `refreshPermissions updates permission states`() = runTest {
        stateManager.uiState.test {
            val initial = awaitItem()
            assertFalse(initial.canDrawOverlays)

            every { PermissionUtils.canDrawOverlays(context) } returns true
            every { PermissionUtils.hasMicrophonePermission(context) } returns true
            every { PermissionUtils.hasNotificationPermission(context) } returns true

            stateManager.refreshPermissions()
            val updated = awaitItem()
            assertTrue(updated.canDrawOverlays)
            assertTrue(updated.hasMicrophonePermission)
            assertTrue(updated.hasNotificationPermission)
        }
    }

    @Test
    fun `setVoiceProcessor delegates to SettingsRepository`() = runTest {
        stateManager.uiState.test {
            awaitItem()

            stateManager.setVoiceProcessor(Strings.Processors.WHISPER_VULKAN)

            // setVoiceProcessor launches a coroutine on Dispatchers.Main
            // which calls repo.setVoiceProcessor — verify the call
            testScheduler.advanceUntilIdle()
            coVerify { settingsRepo.setVoiceProcessor(Strings.Processors.WHISPER_VULKAN) }
        }
    }

    @Test
    fun `setAiProcessor delegates to SettingsRepository`() = runTest {
        stateManager.uiState.test {
            awaitItem()

            stateManager.setAiProcessor(Strings.AiProcessors.GEMINI_NATIVE)

            testScheduler.advanceUntilIdle()
            coVerify { settingsRepo.setAiProcessor(Strings.AiProcessors.GEMINI_NATIVE) }
        }
    }

    @Test
    fun `setVoiceLanguage delegates to SettingsRepository`() = runTest {
        stateManager.uiState.test {
            awaitItem()

            stateManager.setVoiceLanguage("de")

            testScheduler.advanceUntilIdle()
            coVerify { settingsRepo.setVoiceLanguage("de") }
        }
    }

    @Test
    fun `setCloudIntelligenceEnabled delegates to SettingsRepository`() = runTest {
        stateManager.uiState.test {
            awaitItem()

            stateManager.setCloudIntelligenceEnabled(false)

            testScheduler.advanceUntilIdle()
            coVerify { settingsRepo.setCloudIntelligenceEnabled(false) }
        }
    }

    @Test
    fun `setEngineApiKey delegates to SettingsRepository, keyed by engine`() = runTest {
        stateManager.uiState.test {
            awaitItem()

            stateManager.setEngineApiKey(Strings.AiProcessors.OPENAI, "new-key")

            testScheduler.advanceUntilIdle()
            coVerify { settingsRepo.setEngineApiKey(Strings.AiProcessors.OPENAI, "new-key") }
        }
    }

    @Test
    fun `setWakeWordServiceListening updates runtime state`() = runTest {
        stateManager.uiState.test {
            val initial = awaitItem()
            assertFalse(initial.isWakeWordServiceListening)

            stateManager.setWakeWordServiceListening(true)
            val updated = awaitItem()
            assertTrue(updated.isWakeWordServiceListening)
        }
    }

    @Test
    fun `updateBenchmarkResult adds to benchmarkResults list`() = runTest {
        stateManager.benchmarkResults.test {
            assertEquals(emptyList<BenchmarkResult>(), awaitItem())

            val result = BenchmarkResult(engine = "whisper", model = "base", inferenceTimeMs = 5000L, rtf = 0.5f, isSuccess = true)
            stateManager.updateBenchmarkResult(result)
            val updated = awaitItem()
            assertEquals(1, updated.size)
            assertEquals("base", updated[0].model)
        }
    }

    @Test
    fun `clearBenchmarkResults empties the list`() = runTest {
        stateManager.benchmarkResults.test {
            assertEquals(emptyList<BenchmarkResult>(), awaitItem())

            stateManager.updateBenchmarkResult(BenchmarkResult(engine = "whisper", model = "base", inferenceTimeMs = 5000L, rtf = 0.5f, isSuccess = true))
            awaitItem()

            stateManager.clearBenchmarkResults()
            val updated = awaitItem()
            assertEquals(emptyList<BenchmarkResult>(), updated)
        }
    }

    @Test
    fun `setSystemInfo updates systemInfo flow`() = runTest {
        stateManager.systemInfo.test {
            assertEquals("", awaitItem())

            stateManager.setSystemInfo("Android 14, 8GB RAM")
            val updated = awaitItem()
            assertEquals("Android 14, 8GB RAM", updated)
        }
    }
}
