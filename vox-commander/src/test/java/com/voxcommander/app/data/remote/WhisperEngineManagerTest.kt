package com.voxcommander.app.data.remote

import android.content.Context
import com.voxcommander.app.data.preferences.SettingsRepository
import com.voxcommander.app.testutil.TestDataFactory
import com.voxcommander.app.utils.Strings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class WhisperEngineManagerTest {

    private lateinit var context: Context
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var manager: WhisperEngineManager
    private lateinit var tempDir: File
    private lateinit var libDir: File
    private lateinit var appInfo: android.content.pm.ApplicationInfo

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0

        mockkObject(com.voxcommander.app.utils.Logger)
        every { com.voxcommander.app.utils.Logger.log(any(), any()) } returns Unit

        tempDir = File(System.getProperty("java.io.tmpdir"), "vox_whisper_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        context = mockk(relaxed = true)
        settingsRepo = mockk(relaxed = true)

        // filesDir for libDir
        val filesDir = File(tempDir, "files").apply { mkdirs() }
        every { context.filesDir } returns filesDir
        libDir = File(filesDir, "whisper_libs")

        // applicationInfo for nativeLibraryDir
        appInfo = android.content.pm.ApplicationInfo()
        appInfo.nativeLibraryDir = File(tempDir, "nativeLibs").apply { mkdirs() }.absolutePath
        every { context.applicationInfo } returns appInfo

        // External files dir for model deletion
        every { context.getExternalFilesDir(null) } returns File(tempDir, "external").apply { mkdirs() }

        manager = WhisperEngineManager(context, settingsRepo)
    }

    @Test
    fun `areLibsDownloaded returns false when no libs exist`() {
        assertFalse(manager.areLibsDownloaded())
    }

    @Test
    fun `areLibsDownloaded returns true when all libs present`() {
        libDir.mkdirs()
        WhisperEngineManager.WHISPER_LIBS.forEach { libName ->
            File(libDir, libName).writeText("fake lib")
        }

        assertTrue(manager.areLibsDownloaded())
    }

    @Test
    fun `areLibsDownloaded returns false when only some libs present`() {
        libDir.mkdirs()
        File(libDir, WhisperEngineManager.WHISPER_LIBS.first()).writeText("fake lib")

        assertFalse(manager.areLibsDownloaded())
    }

    @Test
    fun `isWhisperAvailable returns true when system has libs`() {
        val systemDir = File(appInfo.nativeLibraryDir)
        WhisperEngineManager.WHISPER_LIBS.forEach { libName ->
            File(systemDir, libName).writeText("system lib")
        }

        assertTrue(manager.isWhisperAvailable())
    }

    @Test
    fun `isWhisperAvailable returns true when downloaded libs exist`() {
        // Clear system libs
        val systemDir = File(appInfo.nativeLibraryDir)
        systemDir.listFiles()?.forEach { it.delete() }

        libDir.mkdirs()
        WhisperEngineManager.WHISPER_LIBS.forEach { libName ->
            File(libDir, libName).writeText("fake lib")
        }

        assertTrue(manager.isWhisperAvailable())
    }

    @Test
    fun `isWhisperAvailable returns false when no libs anywhere`() {
        val systemDir = File(appInfo.nativeLibraryDir)
        systemDir.listFiles()?.forEach { it.delete() }

        assertFalse(manager.isWhisperAvailable())
    }

    @Test
    fun `disable with deleteLibs removes all so files from libDir`() = runTest {
        libDir.mkdirs()
        WhisperEngineManager.WHISPER_LIBS.forEach { libName ->
            File(libDir, libName).writeText("fake lib")
        }
        assertTrue(libDir.exists())

        manager.disable(deleteLibs = true, deleteModels = false)

        assertFalse(libDir.exists())
        coVerify { settingsRepo.setWhisperSystemEnabled(false) }
    }

    @Test
    fun `disable with deleteModels removes bin files and clears settings`() = runTest {
        val externalDir = File(tempDir, "external")
        File(externalDir, "base.bin").writeText("model")
        File(externalDir, "tiny.bin").writeText("model")
        File(externalDir, "notabin.txt").writeText("text")

        mockkObject(RemoteModelRegistry)
        every { RemoteModelRegistry.getEngineKeyByExtension(".bin") } returns "stt_whisper"

        manager.disable(deleteLibs = false, deleteModels = true)

        assertFalse(File(externalDir, "base.bin").exists())
        assertFalse(File(externalDir, "tiny.bin").exists())
        assertTrue(File(externalDir, "notabin.txt").exists())
        coVerify { settingsRepo.setModelDownloaded("base", false) }
        coVerify { settingsRepo.setModelDownloaded("tiny", false) }
        coVerify { settingsRepo.setActiveVoiceModelId(null) }
    }

    @Test
    fun `disable with deleteModels false keeps bin files`() = runTest {
        val externalDir = File(tempDir, "external")
        File(externalDir, "base.bin").writeText("model")

        manager.disable(deleteLibs = false, deleteModels = false)

        assertTrue(File(externalDir, "base.bin").exists())
    }

    @Test
    fun `disable sets whisperSystemEnabled to false`() = runTest {
        manager.disable(deleteLibs = false, deleteModels = false)

        coVerify { settingsRepo.setWhisperSystemEnabled(false) }
    }
}
