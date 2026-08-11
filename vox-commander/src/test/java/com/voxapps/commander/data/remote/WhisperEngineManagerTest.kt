package com.voxapps.commander.data.remote

import android.content.Context
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.testutil.TestDataFactory
import com.voxapps.commander.utils.Strings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
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

        mockkObject(com.voxapps.logging.Logger)
        every { com.voxapps.logging.Logger.log(any(), any()) } returns Unit

        tempDir = File(System.getProperty("java.io.tmpdir"), "vox_whisper_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        context = mockk(relaxed = true)
        settingsRepo = mockk(relaxed = true)

        // filesDir for libDir — derived through the same helper the production readers use, so a
        // renamed directory shows up here instead of leaving the test probing the old name.
        val filesDir = File(tempDir, "files").apply { mkdirs() }
        every { context.filesDir } returns filesDir
        libDir = WhisperEngineManager.libDir(context)

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

    // ── staleness, adoption and verification ──────────────────────────────────────────────────

    private val commit = "a".repeat(40)

    /** Wires real asset streams; without this the relaxed mock reads as "no assets recorded". */
    private fun mockAssets(digestLines: String? = null, commitText: String? = commit) {
        val assets = mockk<android.content.res.AssetManager>()
        if (commitText != null) {
            every { assets.open("whisper-libs.commit") } answers { commitText.byteInputStream() }
        } else {
            every { assets.open("whisper-libs.commit") } throws java.io.FileNotFoundException()
        }
        if (digestLines != null) {
            every { assets.open("whisper-libs.sha256") } answers { digestLines.byteInputStream() }
        } else {
            every { assets.open("whisper-libs.sha256") } throws java.io.FileNotFoundException()
        }
        every { context.assets } returns assets
    }

    private fun sha256(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /** An offline client: every request gets this status and body, freshly built per call. */
    private fun clientReturning(code: Int, body: String = ""): OkHttpClient {
        val client = mockk<OkHttpClient>()
        every { client.newCall(any()) } answers {
            val request = firstArg<Request>()
            val response = Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("test")
                .body(body.toResponseBody(null))
                .build()
            mockk<Call> { every { execute() } returns response }
        }
        return client
    }

    @Test
    fun `areLibsDownloaded returns false when the marker names another build`() {
        mockAssets(commitText = commit)
        libDir.mkdirs()
        WhisperEngineManager.WHISPER_LIBS.forEach { File(libDir, it).writeText("old lib") }
        File(libDir, ".whisper-commit").writeText("b".repeat(40))

        assertFalse(WhisperEngineManager(context, settingsRepo).areLibsDownloaded())
    }

    @Test
    fun `areLibsDownloaded returns true when the marker matches this build`() {
        mockAssets(commitText = commit)
        libDir.mkdirs()
        WhisperEngineManager.WHISPER_LIBS.forEach { File(libDir, it).writeText("lib") }
        File(libDir, ".whisper-commit").writeText(commit)

        assertTrue(WhisperEngineManager(context, settingsRepo).areLibsDownloaded())
    }

    @Test
    fun `needsRefresh notices an empty library file`() {
        libDir.mkdirs()
        File(libDir, WhisperEngineManager.WHISPER_LIBS[0]).writeText("lib")
        File(libDir, WhisperEngineManager.WHISPER_LIBS[1]).writeText("")

        assertTrue(manager.needsRefresh())
    }

    @Test
    fun `needsRefresh is false when the system dir carries the libraries`() {
        val systemDir = File(appInfo.nativeLibraryDir)
        WhisperEngineManager.WHISPER_LIBS.forEach { File(systemDir, it).writeText("system lib") }

        assertTrue(WhisperEngineManager(context, settingsRepo).needsRefresh().not())
    }

    @Test
    fun `downloadLibs adopts files matching the recorded digests and stamps the marker`() = runTest {
        libDir.mkdirs()
        val contents = WhisperEngineManager.WHISPER_LIBS.associateWith { "$it genuine bytes" }
        contents.forEach { (name, bytes) -> File(libDir, name).writeText(bytes) }
        mockAssets(
            digestLines = contents.entries.joinToString("\n") { "${sha256(it.value)}  ${it.key}" },
            commitText = commit
        )

        // Any network call is a failure here: everything on disk verifies, so nothing may fetch.
        val m = WhisperEngineManager(context, settingsRepo, clientReturning(500))
        assertTrue(m.downloadLibs())
        assertEquals(commit, File(libDir, ".whisper-commit").readText())
    }

    @Test
    fun `downloadLibs replaces files that do not match the recorded digest`() = runTest {
        libDir.mkdirs()
        val goodBytes = "genuine library bytes"
        WhisperEngineManager.WHISPER_LIBS.forEach { File(libDir, it).writeText("tampered") }
        mockAssets(
            digestLines = WhisperEngineManager.WHISPER_LIBS
                .joinToString("\n") { "${sha256(goodBytes)}  $it" },
            commitText = commit
        )

        val m = WhisperEngineManager(context, settingsRepo, clientReturning(200, goodBytes))
        assertTrue(m.downloadLibs())
        WhisperEngineManager.WHISPER_LIBS.forEach {
            assertEquals(goodBytes, File(libDir, it).readText())
        }
    }

    @Test
    fun `downloadLibs rejects a download that fails verification and leaves nothing behind`() = runTest {
        libDir.mkdirs()
        mockAssets(
            digestLines = WhisperEngineManager.WHISPER_LIBS
                .joinToString("\n") { "${sha256("expected bytes")}  $it" },
            commitText = commit
        )

        val m = WhisperEngineManager(context, settingsRepo, clientReturning(200, "wrong bytes"))
        assertFalse(m.downloadLibs())
        assertFalse(File(libDir, WhisperEngineManager.WHISPER_LIBS[0]).exists())
        assertFalse(File(libDir, ".whisper-commit").exists())
    }
}
