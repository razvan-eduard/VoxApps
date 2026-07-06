package com.voxcommander.app.domain

import android.content.Context
import android.util.Log
import com.voxcommander.app.data.preferences.AppSettings
import com.voxcommander.app.data.preferences.SettingsRepository
import com.voxcommander.app.data.remote.ModelDownloader
import com.voxcommander.app.domain.localization.LanguageManager
import com.voxcommander.app.state.AppStateManager
import com.voxcommander.app.ui.viewmodels.ModelManagementViewModel
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FallbackCleanupTest {

    private lateinit var settingsRepo: SettingsRepository
    private lateinit var appStateManager: AppStateManager
    private lateinit var modelDownloader: ModelDownloader
    private lateinit var viewModel: ModelManagementViewModel
    private lateinit var languageManager: LanguageManager
    private lateinit var context: Context

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0

        context = mockk(relaxed = true)
        settingsRepo = mockk(relaxed = true)
        appStateManager = mockk(relaxed = true)
        modelDownloader = mockk(relaxed = true)
        languageManager = mockk(relaxed = true)

        // Mock settings flow
        val settingsFlow = MutableStateFlow(AppSettings())
        every { settingsRepo.settingsFlow } returns settingsFlow
        every { settingsRepo.getSettingsSnapshot() } returns AppSettings()

        viewModel = ModelManagementViewModel(
            settingsRepo,
            appStateManager,
            modelDownloader,
            languageManager,
            context
        )
    }

    @Test
    fun `deleteUnusedModels delegates to ModelDownloader with active model IDs`() = runTest {
        val settings = AppSettings(
            voiceProcessor = "stt_whisper",
            activeVoiceModelId = "base",
            aiProcessor = "nlu_llm",
            activeIntentModelId = "qwen2.5-1.5b-q8",
            wakeWordModelPath = "/data/user/0/com.voxcommander.app/files/vosk/vosk-ro"
        )
        every { settingsRepo.getSettingsSnapshot() } returns settings

        viewModel.deleteUnusedModels()

        // Verify deleteUnusedModels was called with the correct active model IDs
        coVerify {
            modelDownloader.deleteUnusedModels(
                settingsRepo,
                "base",
                "qwen2.5-1.5b-q8",
                appStateManager,
                "vosk-ro"
            )
        }
    }

    @Test
    fun `deleteUnusedModels with null active IDs still calls ModelDownloader`() = runTest {
        val settings = AppSettings()
        every { settingsRepo.getSettingsSnapshot() } returns settings

        viewModel.deleteUnusedModels()

        coVerify {
            modelDownloader.deleteUnusedModels(
                settingsRepo,
                null,
                null,
                appStateManager,
                null
            )
        }
    }

    @Test
    fun `clearDefaultOfflineFallback delegates to settingsRepo and refreshes UI`() = runTest {
        viewModel.clearDefaultOfflineFallback()

        coVerify(exactly = 1) { settingsRepo.clearDefaultOfflineFallback() }
        verify { appStateManager.refreshAll() }
    }

    @Test
    fun `deleteModel deletes file and updates settings`() = runTest {
        val modelId = "base"
        val engineKey = "stt_whisper"
        val settings = AppSettings()
        every { settingsRepo.getSettingsSnapshot() } returns settings

        viewModel.deleteModel(modelId, engineKey)

        verify { modelDownloader.deleteModelFile(modelId, engineKey) }
        coVerify { settingsRepo.setModelDownloaded(modelId, false) }
        verify { appStateManager.refreshAll() }
    }

    @Test
    fun `deleteModel reassigns voice fallback when deleted model was fallback`() = runTest {
        val modelId = "base"
        val engineKey = "stt_whisper"
        val settings = AppSettings(
            voiceProcessor = "stt_whisper",
            activeVoiceModelId = "tiny",
            defaultVoiceFallbackModel = "base",
            defaultVoiceFallbackProcessor = "stt_whisper",
            downloadedModelIds = setOf("tiny")
        )
        every { settingsRepo.getSettingsSnapshot() } returns settings

        viewModel.deleteModel(modelId, engineKey)

        coVerify { settingsRepo.setDefaultVoiceFallback("stt_whisper", "tiny") }
    }

    @Test
    fun `deleteModel reassigns intent fallback when deleted model was fallback`() = runTest {
        val modelId = "qwen2.5-1.5b-q8"
        val engineKey = "nlu_llm"
        val settings = AppSettings(
            aiProcessor = "nlu_llm",
            activeIntentModelId = "gemma-3-1b-q8",
            defaultIntentFallbackModel = "qwen2.5-1.5b-q8",
            defaultIntentFallbackProcessor = "nlu_llm",
            downloadedModelIds = setOf("gemma-3-1b-q8")
        )
        every { settingsRepo.getSettingsSnapshot() } returns settings

        viewModel.deleteModel(modelId, engineKey)

        coVerify { settingsRepo.setDefaultIntentFallback("nlu_llm", "gemma-3-1b-q8") }
    }
}
