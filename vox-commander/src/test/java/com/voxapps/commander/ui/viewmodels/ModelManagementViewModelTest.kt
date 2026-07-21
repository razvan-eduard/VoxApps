package com.voxapps.commander.ui.viewmodels

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import com.voxapps.commander.data.preferences.AppSettings
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.data.remote.ModelDownloader
import com.voxapps.commander.data.remote.RemoteModelRegistry
import com.voxapps.commander.domain.localization.LanguageManager
import com.voxapps.commander.domain.model.AppModel
import com.voxapps.commander.state.AppStateManager
import com.voxapps.commander.testutil.TestDataFactory
import com.voxapps.commander.utils.Strings
import io.mockk.Awaits
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ModelManagementViewModelTest {

    private lateinit var settingsRepo: SettingsRepository
    private lateinit var appStateManager: AppStateManager
    private lateinit var modelDownloader: ModelDownloader
    private lateinit var languageManager: LanguageManager
    private lateinit var context: Context
    private lateinit var viewModel: ModelManagementViewModel
    private lateinit var tempDir: File

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0

        mockkObject(com.voxapps.logging.Logger)
        every { com.voxapps.logging.Logger.log(any(), any()) } returns Unit

        Dispatchers.setMain(UnconfinedTestDispatcher())

        tempDir = File(System.getProperty("java.io.tmpdir"), "vox_vm_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        settingsRepo = mockk(relaxed = true)
        appStateManager = mockk(relaxed = true)
        modelDownloader = mockk(relaxed = true)
        languageManager = mockk(relaxed = true)
        context = mockk(relaxed = true)

        every { languageManager.getString(any()) } returns "test-message"

        val downloadManager = mockk<DownloadManager>(relaxed = true)
        every { context.getSystemService(Context.DOWNLOAD_SERVICE) } returns downloadManager
        every { context.getExternalFilesDir(any()) } returns tempDir
        every { context.unregisterReceiver(any()) } returns Unit
        every { context.contentResolver } returns mockk(relaxed = true)
        every { downloadManager.query(any()) } returns null

        mockkObject(RemoteModelRegistry)
        every { RemoteModelRegistry.getEngineKeysByType("voice") } returns listOf("stt_whisper")
        every { RemoteModelRegistry.getEngineKeysByType("llm") } returns listOf("nlu_llm")
        every { RemoteModelRegistry.isZipEngine("stt_whisper") } returns false
        every { RemoteModelRegistry.isZipEngine("nlu_llm") } returns false
        every { RemoteModelRegistry.getExtension("stt_whisper") } returns ".bin"
        every { RemoteModelRegistry.getExtension("nlu_llm") } returns ".gguf"
        every { RemoteModelRegistry.getModels("stt_whisper") } returns listOf(
            TestDataFactory.createRemoteModelItem(id = "base", path = "models/base.bin"),
            TestDataFactory.createRemoteModelItem(id = "tiny", path = "models/tiny.bin")
        )
        every { RemoteModelRegistry.getModels("nlu_llm") } returns listOf(
            TestDataFactory.createRemoteModelItem(id = "qwen", path = "models/qwen.gguf")
        )
        every { RemoteModelRegistry.resolveUrl(any(), any()) } returns "https://example.com/models/base.bin"
        coEvery { RemoteModelRegistry.fetchJson(any(), any()) } returns true
        every { RemoteModelRegistry.registryUpdateSignal } returns kotlinx.coroutines.flow.MutableStateFlow(0L)

        val settings = TestDataFactory.createAppSettings()
        every { settingsRepo.getSettingsSnapshot() } returns settings

        viewModel = ModelManagementViewModel(settingsRepo, appStateManager, modelDownloader, languageManager, context)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        io.mockk.unmockkAll()
        tempDir.deleteRecursively()
    }

    @Test
    fun `downloadModel prevents duplicate when already downloading`() = runTest {
        val item = TestDataFactory.createRemoteModelItem(id = "base")
        // Simulate already downloading by setting downloadingItem via first download
        every { modelDownloader.resolveLocalFile("base", "stt_whisper") } returns File(tempDir, "base.bin")
        every { modelDownloader.downloadModel(any(), any(), any()) } returns -1L

        viewModel.downloadModel("base", "stt_whisper")

        // Second call should be ignored
        viewModel.downloadModel("tiny", "stt_whisper")

        // Only one download should have started
        verify(exactly = 1) { modelDownloader.downloadModel(any(), any(), any()) }
    }

    @Test
    fun `downloadModel with existing file marks as downloaded and selects model`() = runTest {
        val existingFile = File(tempDir, "base.bin")
        existingFile.writeText("already exists")
        every { modelDownloader.resolveLocalFile("base", "stt_whisper") } returns existingFile

        viewModel.downloadModel("base", "stt_whisper")

        coVerify { settingsRepo.setModelDownloaded("base", true) }
        verify { appStateManager.setActiveVoiceModelId("base") }
        verify { appStateManager.saveVoiceModelSelection("stt_whisper", "base") }
        verify { appStateManager.refreshAll() }
    }

    @Test
    fun `downloadModel with existing LLM file selects intent model`() = runTest {
        val existingFile = File(tempDir, "qwen.gguf")
        existingFile.writeText("already exists")
        every { modelDownloader.resolveLocalFile("qwen", "nlu_llm") } returns existingFile

        viewModel.downloadModel("qwen", "nlu_llm")

        coVerify { settingsRepo.setModelDownloaded("qwen", true) }
        verify { appStateManager.setActiveIntentModelId("qwen") }
        verify { appStateManager.saveIntentModelSelection("nlu_llm", "qwen") }
    }

    @Test
    fun `downloadModel starts real download when file not on disk`() = runTest {
        val nonExistent = File(tempDir, "base.bin")
        every { modelDownloader.resolveLocalFile("base", "stt_whisper") } returns nonExistent
        every { modelDownloader.downloadModel(any(), any(), any()) } returns -1L

        viewModel.downloadModel("base", "stt_whisper")

        verify { modelDownloader.downloadModel("base", any(), "stt_whisper") }
    }

    @Test
    fun `selectVoiceModel sets active model and saves selection`() = runTest {
        every { modelDownloader.resolveLocalFile("base", "stt_whisper") } returns File(tempDir, "base.bin")

        viewModel.selectVoiceModel("base", "stt_whisper", "en")

        verify { appStateManager.setModelFilterLang("en") }
        verify { appStateManager.setActiveVoiceModelId("base") }
        coVerify { settingsRepo.setEngineModelSelection("stt_whisper", "base") }
        // No refreshAll(): the STT engine reloads reactively via VoiceManager's observer on
        // activeVoiceModelId, and the UI derives from uiState — bumping refreshTrigger here
        // churned the model dropdown (a re-fire loop), so it was removed.
        verify(exactly = 0) { appStateManager.refreshAll() }
    }

    @Test
    fun `selectVoiceModel marks as downloaded when file exists`() = runTest {
        val existingFile = File(tempDir, "base.bin")
        existingFile.writeText("exists")
        every { modelDownloader.resolveLocalFile("base", "stt_whisper") } returns existingFile

        viewModel.selectVoiceModel("base", "stt_whisper")

        coVerify { settingsRepo.setModelDownloaded("base", true) }
    }

    @Test
    fun `selectVoiceModel does not mark downloaded when file does not exist`() = runTest {
        every { modelDownloader.resolveLocalFile("base", "stt_whisper") } returns File(tempDir, "base.bin")

        viewModel.selectVoiceModel("base", "stt_whisper")

        coVerify(exactly = 0) { settingsRepo.setModelDownloaded(any(), any()) }
    }

    @Test
    fun `selectVoiceModel without langCode does not set language`() = runTest {
        every { modelDownloader.resolveLocalFile("base", "stt_whisper") } returns File(tempDir, "base.bin")

        viewModel.selectVoiceModel("base", "stt_whisper", null)

        verify(exactly = 0) { appStateManager.setVoiceLanguage(any()) }
    }

    @Test
    fun `cancelDownload is a no-op when no active download`() = runTest {
        // Use existing file so downloadModel takes the "already exists" path
        val existingFile = File(tempDir, "base.bin")
        existingFile.writeText("exists")
        every { modelDownloader.resolveLocalFile("base", "stt_whisper") } returns existingFile

        viewModel.downloadModel("base", "stt_whisper")
        // downloadModel with existing file calls refreshAll once
        verify(exactly = 1) { appStateManager.refreshAll() }

        // cancelDownload with no active download should not add extra refreshAll
        viewModel.cancelDownload()
        verify(exactly = 1) { appStateManager.refreshAll() }
    }

    @Test
    fun `clearCustomModel delegates to settingsRepo and refreshes`() = runTest {
        viewModel.clearCustomModel("stt_whisper")

        coVerify { settingsRepo.setCustomModelPath("stt_whisper", "", null) }
        verify { appStateManager.refreshAll() }
    }

    @Test
    fun `clearCustomModel with langCode passes it through`() = runTest {
        viewModel.clearCustomModel("wake_vosk", "en")

        coVerify { settingsRepo.setCustomModelPath("wake_vosk", "", "en") }
    }

    @Test
    fun `clearDefaultOfflineFallback delegates to settingsRepo`() = runTest {
        viewModel.clearDefaultOfflineFallback()

        coVerify { settingsRepo.clearDefaultOfflineFallback() }
        verify { appStateManager.refreshAll() }
    }

    @Test
    fun `deleteModel deletes file and syncs settings`() = runTest {
        viewModel.deleteModel("base", "stt_whisper")

        verify { modelDownloader.deleteModelFile("base", "stt_whisper") }
        coVerify { settingsRepo.setModelDownloaded("base", false) }
        verify { appStateManager.refreshAll() }
    }

    @Test
    fun `deleteModel reassigns voice fallback when deleted model was fallback`() = runTest {
        val settings = TestDataFactory.createAppSettings(
            voiceProcessor = Strings.Processors.WHISPER_VULKAN,
            activeVoiceModelId = "base",
            downloadedModelIds = setOf("base"),
            defaultVoiceFallbackProcessor = Strings.Processors.WHISPER_VULKAN,
            defaultVoiceFallbackModel = "tiny"
        )
        every { settingsRepo.getSettingsSnapshot() } returns settings

        viewModel.deleteModel("tiny", "stt_whisper")

        coVerify { settingsRepo.setDefaultVoiceFallback(Strings.Processors.WHISPER_VULKAN, "base") }
    }

    @Test
    fun `deleteModel clears voice fallback when no active model available`() = runTest {
        val settings = TestDataFactory.createAppSettings(
            voiceProcessor = Strings.Processors.WHISPER_VULKAN,
            activeVoiceModelId = null,
            downloadedModelIds = emptySet(),
            defaultVoiceFallbackProcessor = Strings.Processors.WHISPER_VULKAN,
            defaultVoiceFallbackModel = "tiny"
        )
        every { settingsRepo.getSettingsSnapshot() } returns settings

        viewModel.deleteModel("tiny", "stt_whisper")

        coVerify { settingsRepo.clearDefaultVoiceFallback() }
    }

    @Test
    fun `deleteModel reassigns intent fallback when deleted model was fallback`() = runTest {
        val settings = TestDataFactory.createSettingsWithLlmEngine(
            activeIntentModelId = "qwen",
            downloadedModelIds = setOf("qwen"),
            fallbackModel = "tiny-llm"
        )
        every { settingsRepo.getSettingsSnapshot() } returns settings

        viewModel.deleteModel("tiny-llm", "nlu_llm")

        coVerify { settingsRepo.setDefaultIntentFallback("nlu_llm", "qwen") }
    }

    @Test
    fun `deleteModel clears intent fallback when no active model available`() = runTest {
        val settings = TestDataFactory.createSettingsWithLlmEngine(
            activeIntentModelId = null,
            downloadedModelIds = emptySet(),
            fallbackModel = "tiny-llm"
        )
        every { settingsRepo.getSettingsSnapshot() } returns settings

        viewModel.deleteModel("tiny-llm", "nlu_llm")

        coVerify { settingsRepo.clearDefaultIntentFallback() }
    }

    @Test
    fun `deleteUnusedModels delegates to modelDownloader with active model IDs`() = runTest {
        val settings = TestDataFactory.createAppSettings(
            activeVoiceModelId = "base",
            activeIntentModelId = "qwen"
        )
        every { settingsRepo.getSettingsSnapshot() } returns settings

        viewModel.deleteUnusedModels()

        // Wait for coroutine to execute
        testScheduler.advanceUntilIdle()

        coVerify {
            modelDownloader.deleteUnusedModels(
                settingsRepo,
                "base",
                "qwen",
                appStateManager,
                any()
            )
        }
    }

    @Test
    fun `selectCustomModel with blank extension uses directory-based strategy`() = runTest {
        val uri = mockk<Uri>()
        every { uri.path } returns "/sdcard/vosk-model"
        every { uri.toString() } returns "content://uri"
        every { RemoteModelRegistry.getExtension("wake_vosk") } returns ""

        viewModel.selectCustomModel(uri, "wake_vosk", "en")

        coVerify { settingsRepo.setCustomModelPath("wake_vosk", "/sdcard/vosk-model", "en") }
        verify { appStateManager.refreshAll() }
    }

    @Test
    fun `selectCustomModel with file extension uses file-based strategy`() = runTest {
        val uri = mockk<Uri>()
        every { uri.toString() } returns "content://com.android.providers.documents/document/abc"
        every { RemoteModelRegistry.getExtension("stt_whisper") } returns ".bin"

        mockkObject(com.voxapps.commander.utils.FileHelper)
        every { com.voxapps.commander.utils.FileHelper.copyUriToInternal(any(), any(), any()) } returns "/data/files/stt_whisper.bin"

        viewModel.selectCustomModel(uri, "stt_whisper")

        coVerify { settingsRepo.setCustomModelPath("stt_whisper", "/data/files/stt_whisper.bin") }
        verify { appStateManager.refreshAll() }
    }

    @Test
    fun `selectCustomModel with file extension and copy failure does not set path`() = runTest {
        val uri = mockk<Uri>()
        every { uri.toString() } returns "content://com.android.providers.documents/document/abc"
        every { RemoteModelRegistry.getExtension("stt_whisper") } returns ".bin"

        mockkObject(com.voxapps.commander.utils.FileHelper)
        every { com.voxapps.commander.utils.FileHelper.copyUriToInternal(any(), any(), any()) } returns null

        viewModel.selectCustomModel(uri, "stt_whisper")

        coVerify(exactly = 0) { settingsRepo.setCustomModelPath(any(), any()) }
    }
}
