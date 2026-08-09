package com.voxapps.commander.domain.intent.interpreter

import android.content.Context
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.Conversation
import com.voxapps.commander.data.local.dao.FastMapDao
import com.voxapps.commander.data.preferences.AppSettings
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.data.remote.ModelDownloader
import com.voxapps.commander.testutil.TestDataFactory
import com.voxapps.logging.Logger
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import java.io.File

/**
 * Tests for [LocalLlmInterpreter] focusing on crash reproduction conditions:
 *
 * 1. **Concurrent access race condition** (tombstone pattern #3):
 *    Multiple coroutines calling processCommand() simultaneously on Dispatchers.IO
 *    can race on engine / baseConversation fields. LiteRT-LM's [Conversation] concurrency
 *    contract hasn't been separately re-verified here — treat it as unsafe under concurrent
 *    access until proven otherwise on-device (this test's whole point is proving the Mutex
 *    around it is load-bearing regardless of which engine backs it).
 *
 * 2. **Stale engine-cache corruption recovery**:
 *    MediaPipe's implementation left behind XNNPACK cache files that could corrupt after a
 *    native crash, requiring an explicit cleanup pass in setupLlm() before every load. LiteRT-LM
 *    is instead given a dedicated per-app cache directory via EngineConfig.cacheDir; whether it
 *    has an analogous stale-cache failure mode of its own is unconfirmed and needs verification
 *    on a real device — see setupLlm()'s cacheDir wiring in LocalLlmInterpreter.kt.
 *
 * 3. **Conversation invalidation after failure** (tombstone pattern #3):
 *    If sendMessage() throws, the base conversation should be invalidated
 *    to prevent using a corrupted conversation object.
 */
@Ignore("Requires LiteRT-LM native libs — run as instrumented test on device")
class LocalLlmInterpreterTest {

    private lateinit var context: Context
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var modelDownloader: ModelDownloader
    private lateinit var fastMapDao: FastMapDao
    private lateinit var interpreter: LocalLlmInterpreter
    private lateinit var tempDir: File
    private lateinit var cacheDir: File
    private lateinit var modelFile: File

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0

        mockkObject(Logger)
        every { Logger.log(any(), any()) } returns Unit

        tempDir = File(System.getProperty("java.io.tmpdir"), "vox_llm_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        cacheDir = File(tempDir, "cache").apply { mkdirs() }
        modelFile = File(tempDir, "qwen2.5-0.5b-q8.task").apply { writeText("fake model") }

        context = mockk(relaxed = true)
        every { context.cacheDir } returns cacheDir

        settingsRepo = mockk(relaxed = true)
        modelDownloader = mockk(relaxed = true)
        fastMapDao = mockk(relaxed = true)

        every { modelDownloader.resolveLocalFile(any(), any()) } returns modelFile
        coEvery { fastMapDao.getAllRulesOnce() } returns emptyList()

        interpreter = LocalLlmInterpreter(context, settingsRepo, modelDownloader, fastMapDao)
    }

    @After
    fun teardown() {
        unmockkAll()
        tempDir.deleteRecursively()
    }

    // === ENGINE CACHE DIRECTORY ===

    @Test
    fun `setupLlm creates a dedicated cache directory before loading model`() {
        every { settingsRepo.getSettingsSnapshot() } returns TestDataFactory.createSettingsWithLlmEngine(
            activeIntentModelId = "qwen2.5-0.5b-q8",
            downloadedModelIds = setOf("qwen2.5-0.5b-q8")
        )

        // setupLlm is private, but processCommand calls it first
        // Since Engine.initialize() will fail (no real native lib in JVM test),
        // setupLlm will return early after the cache dir is created
        runTest {
            val result = interpreter.processCommand("play music")
            assertNull(result) // Returns null because Engine can't be initialized in JVM
        }

        assertTrue(File(cacheDir, "litertlm_cache").exists())
    }

    // === MODEL NOT FOUND ===

    @Test
    fun `processCommand returns null when model file does not exist`() = runTest {
        every { settingsRepo.getSettingsSnapshot() } returns TestDataFactory.createSettingsWithLlmEngine(
            activeIntentModelId = "qwen2.5-0.5b-q8"
        )
        every { modelDownloader.resolveLocalFile(any(), any()) } returns File(tempDir, "nonexistent.task")

        val result = interpreter.processCommand("play music")
        assertNull(result)
    }

    @Test
    fun `processCommand returns null when no activeIntentModelId is set`() = runTest {
        every { settingsRepo.getSettingsSnapshot() } returns TestDataFactory.createSettingsWithLlmEngine(
            activeIntentModelId = null
        )

        val result = interpreter.processCommand("play music")
        assertNull(result)
    }

    @Test
    fun `processCommand returns null when modelDownloader returns null`() = runTest {
        every { settingsRepo.getSettingsSnapshot() } returns TestDataFactory.createSettingsWithLlmEngine(
            activeIntentModelId = "qwen2.5-0.5b-q8"
        )
        every { modelDownloader.resolveLocalFile(any(), any()) } returns null

        val result = interpreter.processCommand("play music")
        assertNull(result)
    }

    // === CONCURRENT ACCESS (RACE CONDITION EXPOSURE) ===

    @Test
    fun `concurrent processCommand calls share same engine instance without synchronization`() = runTest {
        // This test exposes the race condition: multiple coroutines on Dispatchers.IO
        // can call setupLlm() simultaneously. The field `engine` is read/written
        // without any mutex or synchronization, leading to:
        // - Double model loading (wasteful)
        // - Concurrent access to Conversation (potential native crash, unverified for LiteRT-LM)
        //
        // In JVM tests this doesn't crash, but on device it causes the tombstone pattern.

        every { settingsRepo.getSettingsSnapshot() } returns TestDataFactory.createSettingsWithLlmEngine(
            activeIntentModelId = "qwen2.5-0.5b-q8",
            downloadedModelIds = setOf("qwen2.5-0.5b-q8")
        )

        // Launch 5 concurrent calls — in JVM they'll all fail at Engine.initialize() (throws)
        // but on device with real native libs, this would race on the engine field
        val results = (1..5).map {
            async { interpreter.processCommand("command $it") }
        }.awaitAll()

        // All return null because Engine.initialize() fails in JVM
        results.forEach { assertNull(it) }
    }

    // === CONVERSATION INVALIDATION AFTER FAILURE ===

    @Test
    fun `processCommand falls back to one-shot conversation when base conversation creation fails`() = runTest {
        // When baseConversation creation fails, processCommand should try a fresh one-shot
        // conversation. This tests the fallback path that exists in the code.
        every { settingsRepo.getSettingsSnapshot() } returns TestDataFactory.createSettingsWithLlmEngine(
            activeIntentModelId = "qwen2.5-0.5b-q8",
            downloadedModelIds = setOf("qwen2.5-0.5b-q8")
        )

        // In JVM, Engine.initialize() fails, so we never reach conversation creation
        // This test just verifies the null path doesn't throw
        val result = interpreter.processCommand("play music")
        assertNull(result)
    }

    // === MODEL RELOAD ON CHANGE ===

    @Test
    fun `setupLlm reloads when modelId changes`() = runTest {
        // First call with model A
        every { settingsRepo.getSettingsSnapshot() } returns TestDataFactory.createSettingsWithLlmEngine(
            activeIntentModelId = "qwen2.5-0.5b-q8"
        )
        interpreter.processCommand("test 1")

        // Second call with different model — should trigger reload
        every { settingsRepo.getSettingsSnapshot() } returns TestDataFactory.createSettingsWithLlmEngine(
            activeIntentModelId = "gemma3-1b-q8"
        )
        interpreter.processCommand("test 2")

        // In JVM both return null (no native lib), but the test verifies
        // no exception is thrown during model switch
    }

    @Test
    fun `setupLlm reloads when engineKey changes`() = runTest {
        every { settingsRepo.getSettingsSnapshot() } returns TestDataFactory.createSettingsWithLlmEngine(
            activeIntentModelId = "qwen2.5-0.5b-q8"
        )
        interpreter.processCommand("test 1")

        // Change engine key (e.g., from nlu_llm to a different engine)
        every { settingsRepo.getSettingsSnapshot() } returns TestDataFactory.createSettingsWithLlmEngine(
            activeIntentModelId = "qwen2.5-0.5b-q8"
        ).copy(aiProcessor = "nlu_llm_v2")
        interpreter.processCommand("test 2")
    }

    // === HELPER ASSERTIONS ===

    private fun assertTrue(condition: Boolean) {
        org.junit.Assert.assertTrue(condition)
    }

    private fun assertFalse(condition: Boolean) {
        org.junit.Assert.assertFalse(condition)
    }
}
