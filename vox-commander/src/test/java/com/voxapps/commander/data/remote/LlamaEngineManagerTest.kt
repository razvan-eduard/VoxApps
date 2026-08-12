package com.voxapps.commander.data.remote

import android.content.Context
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

/** Structural clone of [WhisperEngineManagerTest] for the llama runtime's manager — the download
 *  discipline (adopt-by-digest, .tmp→verify→rename, marker-last) is what these pin. */
class LlamaEngineManagerTest {

    private lateinit var context: Context
    private lateinit var manager: LlamaEngineManager
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

        tempDir = File(System.getProperty("java.io.tmpdir"), "vox_llama_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        context = mockk(relaxed = true)

        // filesDir for libDir — derived through the same helper the production readers use.
        val filesDir = File(tempDir, "files").apply { mkdirs() }
        every { context.filesDir } returns filesDir
        libDir = LlamaEngineManager.libDir(context)

        appInfo = android.content.pm.ApplicationInfo()
        appInfo.nativeLibraryDir = File(tempDir, "nativeLibs").apply { mkdirs() }.absolutePath
        every { context.applicationInfo } returns appInfo

        manager = LlamaEngineManager(context)
    }

    private val commit = "a".repeat(40)

    /** Wires real asset streams; without this the relaxed mock reads as "no assets recorded". */
    private fun mockAssets(digestLines: String? = null, commitText: String? = commit) {
        val assets = mockk<android.content.res.AssetManager>()
        if (commitText != null) {
            every { assets.open("llama-libs.commit") } answers { commitText.byteInputStream() }
        } else {
            every { assets.open("llama-libs.commit") } throws java.io.FileNotFoundException()
        }
        if (digestLines != null) {
            every { assets.open("llama-libs.sha256") } answers { digestLines.byteInputStream() }
        } else {
            every { assets.open("llama-libs.sha256") } throws java.io.FileNotFoundException()
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
    fun `areLibsDownloaded returns false when no libs exist`() {
        assertFalse(manager.areLibsDownloaded())
    }

    @Test
    fun `areLibsDownloaded returns false when the marker names another build`() {
        mockAssets(commitText = commit)
        libDir.mkdirs()
        LlamaEngineManager.LLAMA_LIBS.forEach { File(libDir, it).writeText("old lib") }
        File(libDir, ".llama-commit").writeText("b".repeat(40))

        assertFalse(LlamaEngineManager(context).areLibsDownloaded())
    }

    @Test
    fun `areLibsDownloaded returns true when the marker matches this build`() {
        mockAssets(commitText = commit)
        libDir.mkdirs()
        LlamaEngineManager.LLAMA_LIBS.forEach { File(libDir, it).writeText("lib") }
        File(libDir, ".llama-commit").writeText(commit)

        assertTrue(LlamaEngineManager(context).areLibsDownloaded())
    }

    @Test
    fun `needsRefresh notices an empty library file`() {
        libDir.mkdirs()
        File(libDir, LlamaEngineManager.LLAMA_LIBS[0]).writeText("")

        assertTrue(manager.needsRefresh())
    }

    @Test
    fun `needsRefresh is false when the system dir carries the libraries`() {
        val systemDir = File(appInfo.nativeLibraryDir)
        LlamaEngineManager.LLAMA_LIBS.forEach { File(systemDir, it).writeText("system lib") }

        assertFalse(LlamaEngineManager(context).needsRefresh())
    }

    @Test
    fun `downloadLibs refuses to guess when the build recorded no commit`() = runTest {
        // Unlike whisper there is no legacy shared tag to fall back to: with no recorded commit
        // there is no per-commit release this build can correctly ask for.
        mockAssets(commitText = null)

        assertFalse(LlamaEngineManager(context, clientReturning(200, "anything")).downloadLibs())
    }

    @Test
    fun `downloadLibs adopts files matching the recorded digests and stamps the marker`() = runTest {
        libDir.mkdirs()
        val contents = LlamaEngineManager.LLAMA_LIBS.associateWith { "$it genuine bytes" }
        contents.forEach { (name, bytes) -> File(libDir, name).writeText(bytes) }
        mockAssets(
            digestLines = contents.entries.joinToString("\n") { "${sha256(it.value)}  ${it.key}" },
            commitText = commit
        )

        // Any network call is a failure here: everything on disk verifies, so nothing may fetch.
        val m = LlamaEngineManager(context, clientReturning(500))
        assertTrue(m.downloadLibs())
        assertEquals(commit, File(libDir, ".llama-commit").readText())
    }

    @Test
    fun `a second downloadLibs after adoption fetches nothing`() = runTest {
        libDir.mkdirs()
        val bytes = "genuine library bytes"
        mockAssets(
            digestLines = LlamaEngineManager.LLAMA_LIBS.joinToString("\n") { "${sha256(bytes)}  $it" },
            commitText = commit
        )
        val first = LlamaEngineManager(context, clientReturning(200, bytes))
        assertTrue(first.downloadLibs())

        // The refetch-every-launch failure mode: a marker that never lands, or staleness that
        // misreads it, would send this second call back to the network — which now always fails.
        val second = LlamaEngineManager(context, clientReturning(500))
        assertTrue(second.downloadLibs())
        assertFalse(second.needsRefresh())
    }

    @Test
    fun `downloadLibs replaces files that do not match the recorded digest`() = runTest {
        libDir.mkdirs()
        val goodBytes = "genuine library bytes"
        LlamaEngineManager.LLAMA_LIBS.forEach { File(libDir, it).writeText("tampered") }
        mockAssets(
            digestLines = LlamaEngineManager.LLAMA_LIBS.joinToString("\n") { "${sha256(goodBytes)}  $it" },
            commitText = commit
        )

        val m = LlamaEngineManager(context, clientReturning(200, goodBytes))
        assertTrue(m.downloadLibs())
        LlamaEngineManager.LLAMA_LIBS.forEach {
            assertEquals(goodBytes, File(libDir, it).readText())
        }
    }

    @Test
    fun `downloadLibs rejects a download that fails verification and leaves nothing behind`() = runTest {
        libDir.mkdirs()
        mockAssets(
            digestLines = LlamaEngineManager.LLAMA_LIBS.joinToString("\n") { "${sha256("expected bytes")}  $it" },
            commitText = commit
        )

        val m = LlamaEngineManager(context, clientReturning(200, "wrong bytes"))
        assertFalse(m.downloadLibs())
        assertFalse(File(libDir, LlamaEngineManager.LLAMA_LIBS[0]).exists())
        assertFalse(File(libDir, ".llama-commit").exists())
    }
}
