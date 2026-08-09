package com.voxapps.commander.data.remote

import android.content.Context
import android.os.Environment
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.state.AppStateManager
import com.voxapps.commander.testutil.TestDataFactory
import com.voxapps.commander.utils.Strings
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

        mockkObject(com.voxapps.logging.Logger)
        every { com.voxapps.logging.Logger.log(any(), any()) } returns Unit

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
    fun `a file whose name does not match the engine's extension is refused`() {
        // The destination is named after the engine, so a copied-anyway file would arrive looking
        // exactly like a valid one: pick a .txt for Whisper and it lands as stt_whisper.bin.
        every { RemoteModelRegistry.isArchiveEngine("stt_whisper") } returns false
        val uri = mockk<android.net.Uri>(relaxed = true)
        val cursor = mockk<android.database.Cursor>(relaxed = true)
        every { cursor.moveToFirst() } returns true
        every { cursor.getString(0) } returns "shopping-list.txt"
        every { context.contentResolver.query(uri, any(), any(), any(), any()) } returns cursor

        val outcome = downloader.importCustomModel(uri, "stt_whisper")

        assertTrue(outcome is ModelDownloader.ImportOutcome.WrongKind)
        assertEquals(".bin", (outcome as ModelDownloader.ImportOutcome.WrongKind).expected)
        assertFalse(File(tempDir, "stt_whisper.bin").exists())
    }

    @Test
    fun `a file with the engine's extension is accepted`() {
        every { RemoteModelRegistry.isArchiveEngine("stt_whisper") } returns false
        val uri = mockk<android.net.Uri>(relaxed = true)
        val cursor = mockk<android.database.Cursor>(relaxed = true)
        every { cursor.moveToFirst() } returns true
        every { cursor.getString(0) } returns "ggml-tiny.bin"
        every { context.contentResolver.query(uri, any(), any(), any(), any()) } returns cursor
        every { context.contentResolver.openInputStream(uri) } returns "model".byteInputStream()

        val outcome = downloader.importCustomModel(uri, "stt_whisper")

        assertTrue(outcome is ModelDownloader.ImportOutcome.Accepted)
        val imported = (outcome as ModelDownloader.ImportOutcome.Accepted).file
        assertEquals(File(tempDir, "stt_whisper.bin"), imported)
        assertTrue(imported.exists())
    }

    @Test
    fun `an archive cannot write outside the model directory`() {
        // The entry name comes from inside the archive, and archives arrive from a
        // user-configurable repository or from a file the user picked. "../" in a name is the
        // oldest trick there is, and it lands wherever this app's uid can write.
        every { RemoteModelRegistry.isArchiveEngine("wake_vosk") } returns true
        val outside = File(tempDir, "victim.txt")
        outside.writeText("original")

        // Where unzipModel looks: getExternalFilesDir(DIRECTORY_DOWNLOADS)/<modelId><extension>,
        // which this harness maps to tempDir. Put it anywhere else and the test proves nothing.
        val archive = File(tempDir, "vosk-evil.zip")
        java.util.zip.ZipOutputStream(archive.outputStream()).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("../victim.txt"))
            zos.write("owned".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(java.util.zip.ZipEntry("am/final.mdl"))
            zos.write(ByteArray(16))
            zos.closeEntry()
        }

        var completed = false
        downloader.unzipModel("vosk-evil", "wake_vosk") { completed = true }

        assertTrue("extraction should still finish", completed)
        assertEquals("original", outside.readText())
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

    /**
     * A model the user imported themselves is the one file here that cannot be fetched again — it
     * may be their only copy. The single-file import writes it into this very directory under a
     * name derived from the engine, and the active model id is a registry id rather than that file,
     * so nothing the cleanup protects ever resolved to it: selecting a custom model and then running
     * cleanup deleted it, leaving the selection pointing at nothing.
     */
    @Test
    fun `deleteUnusedModels keeps a model the user imported`() = runBlocking {
        every { RemoteModelRegistry.getExtension(Strings.Processors.WHISPER_VULKAN) } returns ".bin"

        val custom = File(tempDir, "stt_whisper.bin")
        custom.writeText("the user's own model")
        val unused = downloader.resolveLocalFile("tiny", Strings.Processors.WHISPER_VULKAN)!!
        unused.writeText("unused")

        val settings = TestDataFactory.createAppSettings(
            voiceProcessor = Strings.Processors.WHISPER_VULKAN,
            activeVoiceModelId = "base",
            downloadedModelIds = setOf("tiny"),
            customModelPaths = mapOf("stt_whisper" to custom.absolutePath)
        )
        val settingsRepo = mockk<SettingsRepository>(relaxed = true)
        every { settingsRepo.getSettingsSnapshot() } returns settings

        downloader.deleteUnusedModels(settingsRepo, "base", null, null, null)

        assertTrue("the imported model was deleted", custom.exists())
        assertFalse(unused.exists())
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
