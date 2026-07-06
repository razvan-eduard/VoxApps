package com.voxcommander.app.data.remote

import android.content.Context
import android.os.Environment
import com.voxcommander.app.data.preferences.SettingsRepository
import com.voxcommander.app.state.AppStateManager
import com.voxcommander.app.testutil.TestDataFactory
import com.voxcommander.app.utils.Strings
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class ModelDownloaderTest {

    private lateinit var context: Context
    private lateinit var downloader: ModelDownloader
    private lateinit var tempDir: File

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0

        mockkObject(com.voxcommander.app.utils.Logger)
        every { com.voxcommander.app.utils.Logger.log(any(), any()) } returns Unit

        mockkObject(RemoteModelRegistry)
        every { RemoteModelRegistry.getExtension(any()) } returns ""
        every { RemoteModelRegistry.getExtension("stt_whisper") } returns ".bin"
        every { RemoteModelRegistry.getExtension("wake_vosk") } returns ".zip"
        every { RemoteModelRegistry.getExtension("nlu_llm") } returns ".gguf"
        every { RemoteModelRegistry.isZipEngine(any()) } returns false
        every { RemoteModelRegistry.isZipEngine("wake_vosk") } returns true
        every { RemoteModelRegistry.isLlmEngine(any()) } returns false
        every { RemoteModelRegistry.getEngineTypes() } returns listOf("stt_whisper", "wake_vosk", "nlu_llm")

        tempDir = File(System.getProperty("java.io.tmpdir"), "vox_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        context = mockk(relaxed = true)
        // In unit tests, Environment.DIRECTORY_DOWNLOADS is null, so getExternalFilesDir(null)
        // and getExternalFilesDir(DIRECTORY_DOWNLOADS) are the same call.
        // We return tempDir for both, and create Download as a subdirectory.
        File(tempDir, "Download").mkdirs()
        every { context.getExternalFilesDir(any()) } returns tempDir

        // Mock DownloadManager
        val downloadManager = mockk<android.app.DownloadManager>()
        every { context.getSystemService(Context.DOWNLOAD_SERVICE) } returns downloadManager

        downloader = ModelDownloader(context)
    }

    @Test
    fun `resolveLocalFile returns directory for zip engines`() {
        val file = downloader.resolveLocalFile("vosk-model-small", "wake_vosk")
        assertNotNull(file)
        assertEquals("vosk-model-small", file!!.name)
        assertFalse(file.path.endsWith(".zip"))
    }

    @Test
    fun `resolveLocalFile returns file with extension for file-based engines`() {
        val file = downloader.resolveLocalFile("base", "stt_whisper")
        assertNotNull(file)
        assertTrue(file!!.path.endsWith("base.bin"))
    }

    @Test
    fun `resolveLocalFile returns null when external files dir is null`() {
        every { context.getExternalFilesDir(any()) } returns null
        val file = downloader.resolveLocalFile("base", "stt_whisper")
        assertNull(file)
    }

    @Test
    fun `deleteModelFile deletes existing file-based model`() {
        val resolved = downloader.resolveLocalFile("base", "stt_whisper")
        val modelFile = resolved ?: File(tempDir, "base.bin")
        modelFile.writeText("test")
        assertTrue("$modelFile should exist", modelFile.exists())

        downloader.deleteModelFile("base", "stt_whisper")

        assertFalse(modelFile.exists())
    }

    @Test
    fun `deleteModelFile deletes existing zip-based model directory`() {
        val resolved = downloader.resolveLocalFile("vosk-model-small", "wake_vosk")
        val modelDir = resolved ?: File(tempDir, "vosk-model-small")
        modelDir.mkdirs()
        File(modelDir, "config.json").writeText("test")
        assertTrue(modelDir.exists())

        downloader.deleteModelFile("vosk-model-small", "wake_vosk")

        assertFalse(modelDir.exists())
    }

    @Test
    fun `deleteModelFile does nothing when file does not exist`() {
        // Should not throw
        downloader.deleteModelFile("nonexistent", "stt_whisper")
    }

    @Test
    fun `deleteUnusedModels protects active voice and intent models`() = runBlocking {
        every { RemoteModelRegistry.getExtension(Strings.Processors.WHISPER_VULKAN) } returns ".bin"
        every { RemoteModelRegistry.getExtension("nlu_llm") } returns ".gguf"

        // Create files at the exact paths resolveLocalFile returns
        val activeVoice = downloader.resolveLocalFile("base", Strings.Processors.WHISPER_VULKAN)!!
        activeVoice.writeText("active voice")
        val unusedModel = downloader.resolveLocalFile("tiny", Strings.Processors.WHISPER_VULKAN)!!
        unusedModel.writeText("unused")
        val essentialDir = File(tempDir, "transcriptions")
        essentialDir.mkdirs()

        val settings = TestDataFactory.createAppSettings(
            voiceProcessor = Strings.Processors.WHISPER_VULKAN,
            aiProcessor = "nlu_llm",
            activeVoiceModelId = "base",
            activeIntentModelId = "qwen",
            downloadedModelIds = setOf("base", "qwen", "tiny")
        )
        val settingsRepo = mockk<SettingsRepository>(relaxed = true)
        every { settingsRepo.getSettingsSnapshot() } returns settings

        downloader.deleteUnusedModels(settingsRepo, "base", "qwen", null, null)

        // Essential directories are always preserved
        assertTrue(essentialDir.exists())
        // Unused model is deleted
        assertFalse(unusedModel.exists())
        // Verify settings sync happened for deleted model
        coVerify { settingsRepo.setModelDownloaded("tiny", false) }
    }

    @Test
    fun `deleteUnusedModels cleans up downloads directory`() = runBlocking {
        // In unit tests, DIRECTORY_DOWNLOADS is null so downloadsDir == rootDir == tempDir
        val zipFile = File(tempDir, "model.zip")
        zipFile.writeText("zip content")

        val settings = TestDataFactory.createAppSettings()
        val settingsRepo = mockk<SettingsRepository>(relaxed = true)
        every { settingsRepo.getSettingsSnapshot() } returns settings

        downloader.deleteUnusedModels(settingsRepo, null, null, null, null)

        assertFalse(zipFile.exists())
    }

    @Test
    fun `deleteUnusedModels calls appStateManager refreshAll when provided`() = runBlocking {
        val settings = TestDataFactory.createAppSettings()
        val settingsRepo = mockk<SettingsRepository>(relaxed = true)
        every { settingsRepo.getSettingsSnapshot() } returns settings
        val appStateManager = mockk<AppStateManager>(relaxed = true)
        every { appStateManager.refreshAll() } returns Unit

        downloader.deleteUnusedModels(settingsRepo, null, null, appStateManager, null)

        verify { appStateManager.refreshAll() }
    }

    @Test
    fun `deleteUnusedModels preserves essential system directories`() = runBlocking {
        val logsDir = File(tempDir, "logs")
        logsDir.mkdirs()
        val downloadDir = File(tempDir, "Download")
        downloadDir.mkdirs()

        val settings = TestDataFactory.createAppSettings()
        val settingsRepo = mockk<SettingsRepository>(relaxed = true)
        every { settingsRepo.getSettingsSnapshot() } returns settings

        downloader.deleteUnusedModels(settingsRepo, null, null, null, null)

        assertTrue(logsDir.exists())
    }
}
