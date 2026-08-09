package com.voxapps.commander

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.data.remote.ModelDownloader
import com.voxapps.commander.domain.intent.interpreter.LocalLlmInterpreter
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumented tests that reproduce the three native crash patterns found in tombstones.
 *
 * **WARNING:** Some tests in this class intentionally trigger native crashes (SIGSEGV/SIGILL).
 * These tests are designed to FAIL (crash the process) to confirm the bug exists.
 * They are annotated with @Test and named with `_crashReproduction` suffix.
 *
 * Run these tests individually:
 *   ./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.voxapps.commander.NativeCrashReproductionTest#testName
 *
 * Tombstone patterns reproduced:
 *
 * 1. **On-device LLM SIGSEGV** (tombstone_08-11, 16-21):
 *    - Signal 11 (SIGSEGV), null pointer dereference at 0x0
 *    - Originally reproduced against MediaPipe GenAI's libllm_inference_engine_jni.so →
 *      LlmTaskRunner.nativePredictSync; the engine has since been migrated to LiteRT-LM
 *      (liblitertlm_jni.so, Engine/Conversation API). Whether LiteRT-LM's Conversation has the
 *      same concurrent-access hazard as MediaPipe's LlmInferenceSession is unconfirmed — this
 *      test still exists to prove the Mutex in LocalLlmInterpreter is load-bearing regardless of
 *      which engine backs it.
 *    - Thread: DefaultDispatch (Dispatchers.IO coroutine)
 *    - Root cause (as originally diagnosed): concurrent access to a shared session/conversation
 *      object from multiple coroutines
 *
 * 2. **Whisper.cpp SIGILL** (tombstone_12-15):
 *    - Signal 4 (SIGILL), illegal instruction
 *    - In libwhisper.so → ggml_cpu_init → ggml_backend_cpu_reg
 *    - Thread: DefaultDispatch
 *    - Root cause: ggml backend registry using unsupported CPU instruction on Honor N39
 *
 * 3. **Vulkan probe SIGSEGV** (tombstone_00-07):
 *    - Signal 11 (SIGSEGV) in com.voxapps.commander:vulkanprobe process
 *    - In Vulkan inference during whisper_init_with_params
 *    - Root cause: GPU driver incompatibility, already handled by VulkanProbeService isolation
 */
@RunWith(AndroidJUnit4::class)
class NativeCrashReproductionTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    // ================================================================
    // Pattern 1: On-device LLM SIGSEGV — concurrent access
    // ================================================================

    /**
     * Reproduces tombstone pattern #3: SIGSEGV originally found in MediaPipe's
     * LlmTaskRunner.nativePredictSync, now re-run against LiteRT-LM's Engine/Conversation.
     *
     * Multiple concurrent coroutines calling processCommand() on Dispatchers.IO race on the
     * shared engine/conversation fields inside LocalLlmInterpreter. Whether LiteRT-LM's
     * Conversation is thread-safe under concurrent sendMessage() calls is unconfirmed — this test
     * exists specifically to catch a regression either way, since the Mutex in
     * LocalLlmInterpreter is the only thing standing between this and a crash.
     *
     * **Expected result:** Process crash (SIGSEGV) — test will fail with process death.
     * If the test PASSES without crash, the concurrency issue has been fixed (or the Mutex is
     * doing its job).
     *
     * Prerequisites: A downloaded LLM model (e.g., qwen2.5-0.5b-q8) must exist.
     */
    @Test
    fun `llm_concurrentPredictSync_crashReproduction`() = runBlocking {
        val settingsRepo = getSettingsRepo()
        val modelDownloader = getModelDownloader()
        val interpreter = LocalLlmInterpreter(context, settingsRepo, modelDownloader)

        // Check if model is available
        val snapshot = settingsRepo.getSettingsSnapshot()
        val modelId = snapshot.activeIntentModelId
        if (modelId == null) {
            android.util.Log.w("NativeCrashTest", "No activeIntentModelId — skipping LLM crash test")
            return@runBlocking
        }

        val modelFile = modelDownloader.resolveLocalFile(modelId, snapshot.aiProcessor)
        if (modelFile == null || !modelFile.exists()) {
            android.util.Log.w("NativeCrashTest", "LLM model $modelId not downloaded — skipping crash test")
            return@runBlocking
        }

        android.util.Log.i("NativeCrashTest", "Starting concurrent LLM calls with model: $modelId")

        // Launch 3 concurrent processCommand calls — this races on engine/baseConversation
        // On device with real native libs, this would trigger a crash if the Mutex weren't there
        val results = (1..3).map { i ->
            async { interpreter.processCommand("play song number $i") }
        }.awaitAll()

        // If we reach here without crash, the concurrency issue is fixed
        android.util.Log.i("NativeCrashTest", "Concurrent LLM calls completed without crash: $results")
    }

    // ================================================================
    // Pattern 2: Whisper.cpp SIGILL — illegal instruction in ggml
    // ================================================================

    /**
     * Reproduces tombstone pattern #2: SIGILL in libwhisper.so → ggml_cpu_init.
     *
     * Whisper.cpp's ggml backend registry tries to detect CPU features and
     * may execute an instruction not supported by the device's SoC
     * (Honor N39 / Snapdragon 7 Gen 1).
     *
     * **Expected result:** Process crash (SIGILL) — test will fail with process death.
     * If the test PASSES without crash, the SIGILL issue has been fixed
     * (e.g., ggml compiled with correct CPU flags).
     *
     * Prerequisites: Whisper native libraries must be loaded (system or downloaded).
     */
    @Test
    fun `whisper_ggmlCpuInit_sigillCrashReproduction`() {
        android.util.Log.i("NativeCrashTest", "Starting Whisper ggml_cpu_init — may SIGILL on this device")

        // This calls whisper_init_from_file_with_params which goes through:
        // ggml_backend_dev_count → ggml_backend_registry → ggml_backend_cpu_reg → ggml_cpu_init
        // If ggml_cpu_init uses an unsupported instruction, SIGILL occurs here
        val modelDir = context.getExternalFilesDir(null)
        val modelFile = File(modelDir, "base.bin")

        if (!modelFile.exists()) {
            android.util.Log.w("NativeCrashTest", "Whisper model base.bin not found — skipping SIGILL test")
            return
        }

        // This is the call that triggers SIGILL in ggml_cpu_init
        val whisperContext = WhisperContext.createContextFromFile(modelFile.absolutePath, useGpu = false)

        // If we reach here without crash, the SIGILL issue is fixed
        android.util.Log.i("NativeCrashTest", "Whisper initContext completed without SIGILL")
        assertNotNull("Whisper context should be created", whisperContext)
        whisperContext.release()
    }

    /**
     * Tests that whisper init with a corrupted/empty model file is handled gracefully.
     */
    @Test
    fun `whisper_corruptedModel_doesNotCrash`() {
        // Create a fake/corrupted model file
        val modelDir = context.getExternalFilesDir(null)
        val fakeModel = File(modelDir, "fake_test_model.bin")
        fakeModel.writeText("this is not a valid ggml model file")

        android.util.Log.i("NativeCrashTest", "Testing whisper init with corrupted model")

        // This should throw (createContextFromFile throws RuntimeException on a null native
        // pointer), NOT crash with SIGSEGV
        var whisperContext: WhisperContext? = null
        try {
            whisperContext = WhisperContext.createContextFromFile(fakeModel.absolutePath, useGpu = false)
            android.util.Log.w("NativeCrashTest", "Whisper init returned non-null for corrupted model: $whisperContext")
        } catch (e: RuntimeException) {
            android.util.Log.i("NativeCrashTest", "Whisper init correctly threw exception for corrupted model: ${e.message}")
        } finally {
            whisperContext?.release()
            fakeModel.delete()
        }
    }

    // ================================================================
    // Pattern 3: Vulkan probe SIGSEGV — already isolated
    // ================================================================

    /**
     * Verifies that VulkanProbeService runs in a separate process (:vulkanprobe)
     * so that a SIGSEGV during GPU inference doesn't kill the main app process.
     *
     * This is a non-crash test — it verifies the isolation mechanism works.
     */
    @Test
    fun `vulkan_probeService_isIsolatedProcess`() {
        val pm = context.packageManager
        val serviceInfo = pm.getServiceInfo(
            android.content.ComponentName(context, com.voxapps.commander.domain.diagnostic.VulkanProbeService::class.java),
            android.content.pm.PackageManager.GET_META_DATA
        )

        assertNotNull("VulkanProbeService should be registered", serviceInfo)

        // Check if the service declares android:process=":vulkanprobe"
        // The process name should contain "vulkanprobe"
        val processName = serviceInfo.processName
        android.util.Log.i("NativeCrashTest", "VulkanProbeService process: $processName")

        // If processName is set to :vulkanprobe, the service runs in a separate process
        // and a crash there won't kill the main app
        assertTrue(
            "VulkanProbeService should run in isolated process (:vulkanprobe), got: $processName",
            processName != null && processName.contains("vulkanprobe")
        )
    }

    // ================================================================
    // Helpers
    // ================================================================

    private fun getSettingsRepo(): SettingsRepository {
        val appContext = context.applicationContext
        // Use the real AppContainer to get the real SettingsRepository
        val container = (appContext as? com.voxapps.commander.VoxApplication)?.container
            ?: throw IllegalStateException("Could not get AppContainer")
        return container.settingsRepository
    }

    private fun getModelDownloader(): ModelDownloader {
        val appContext = context.applicationContext
        val container = (appContext as? com.voxapps.commander.VoxApplication)?.container
            ?: throw IllegalStateException("Could not get AppContainer")
        return container.modelDownloader
    }
}
