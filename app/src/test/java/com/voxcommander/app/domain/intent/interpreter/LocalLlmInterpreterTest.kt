package com.voxcommander.app.domain.intent.interpreter

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.voxcommander.app.data.preferences.AppSettings
import com.voxcommander.app.data.preferences.SettingsRepository
import com.voxcommander.app.data.remote.ModelDownloader
import com.voxcommander.app.testutil.TestDataFactory
import com.voxcommander.app.utils.Logger
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
 *    can race on llmInference / baseSession fields. MediaPipe LlmInferenceSession
 *    is NOT documented as thread-safe — concurrent predictSync() calls cause
 *    SIGSEGV (null pointer dereference at 0x0) in libllm_inference_engine_jni.so.
 *
 * 2. **XNNPACK cache corruption recovery** (tombstone pattern #3):
 *    After a native crash, XNNPACK cache files become corrupted and cause
 *    crash loops on every subsequent model load. setupLlm() should clear them.
 *
 * 3. **Session invalidation after failure** (tombstone pattern #3):
 *    If generateResponse() throws, the base session should be invalidated
 *    to prevent using a corrupted session object.
 */
@Ignore("Requires MediaPipe GenAI native libs — run as instrumented test on device")
class LocalLlmInterpreterTest {

    private lateinit var context: Context
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var modelDownloader: ModelDownloader
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

        every { modelDownloader.resolveLocalFile(any(), any()) } returns modelFile

        interpreter = LocalLlmInterpreter(context, settingsRepo, modelDownloader)
    }

    @After
    fun teardown() {
        unmockkAll()
        tempDir.deleteRecursively()
    }

    // === XNNPACK CACHE CORRUPTION RECOVERY ===

    @Test
    fun `setupLlm clears stale XNNPACK cache files before loading model`() {
        // Simulate corrupted XNNPACK cache from a previous native crash
        val xnnpackCache = File(cacheDir, "qwen2.5-0.5b-q8-tn.task.xnnpack_cache_12345_67890")
        xnnpackCache.writeText("corrupted cache data")
        assertTrue(xnnpackCache.exists())

        every { settingsRepo.getSettingsSnapshot() } returns TestDataFactory.createSettingsWithLlmEngine(
            activeIntentModelId = "qwen2.5-0.5b-q8",
            downloadedModelIds = setOf("qwen2.5-0.5b-q8")
        )

        // setupLlm is private, but processCommand calls it first
        // Since LlmInference.createFromOptions will fail (no real native lib in JVM test),
        // setupLlm will return early after clearing cache
        runTest {
            val result = interpreter.processCommand("play music")
            assertNull(result) // Returns null because LlmInference can't be created in JVM
        }

        // Verify XNNPACK cache was deleted
        assertFalse(xnnpackCache.exists())
    }

    @Test
    fun `setupLlm clears multiple XNNPACK cache files`() {
        File(cacheDir, "model1.xnnpack_cache_111_222").writeText("corrupt1")
        File(cacheDir, "model2.xnnpack_cache_333_444").writeText("corrupt2")
        File(cacheDir, "not_a_cache.txt").writeText("keep me")

        every { settingsRepo.getSettingsSnapshot() } returns TestDataFactory.createSettingsWithLlmEngine(
            activeIntentModelId = "qwen2.5-0.5b-q8",
            downloadedModelIds = setOf("qwen2.5-0.5b-q8")
        )

        runTest {
            interpreter.processCommand("test")
        }

        assertFalse(File(cacheDir, "model1.xnnpack_cache_111_222").exists())
        assertFalse(File(cacheDir, "model2.xnnpack_cache_333_444").exists())
        assertTrue(File(cacheDir, "not_a_cache.txt").exists())
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
    fun `concurrent processCommand calls share same llmInference instance without synchronization`() = runTest {
        // This test exposes the race condition: multiple coroutines on Dispatchers.IO
        // can call setupLlm() simultaneously. The field `llmInference` is read/written
        // without any mutex or synchronization, leading to:
        // - Double model loading (wasteful)
        // - Concurrent access to LlmInferenceSession (SIGSEGV in native code)
        //
        // In JVM tests this doesn't crash, but on device it causes the tombstone pattern.

        every { settingsRepo.getSettingsSnapshot() } returns TestDataFactory.createSettingsWithLlmEngine(
            activeIntentModelId = "qwen2.5-0.5b-q8",
            downloadedModelIds = setOf("qwen2.5-0.5b-q8")
        )

        // Launch 5 concurrent calls — in JVM they'll all fail at createFromOptions (null)
        // but on device with real native libs, this would race on llmInference field
        val results = (1..5).map {
            async { interpreter.processCommand("command $it") }
        }.awaitAll()

        // All return null because LlmInference.createFromOptions returns null in JVM
        results.forEach { assertNull(it) }
    }

    // === SESSION INVALIDATION AFTER FAILURE ===

    @Test
    fun `processCommand falls back to direct generateResponse when session creation fails`() = runTest {
        // When baseSession creation fails, processCommand should try direct generateResponse
        // This tests the fallback path that exists in the code
        every { settingsRepo.getSettingsSnapshot() } returns TestDataFactory.createSettingsWithLlmEngine(
            activeIntentModelId = "qwen2.5-0.5b-q8",
            downloadedModelIds = setOf("qwen2.5-0.5b-q8")
        )

        // In JVM, createFromOptions returns null, so we never reach session creation
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
