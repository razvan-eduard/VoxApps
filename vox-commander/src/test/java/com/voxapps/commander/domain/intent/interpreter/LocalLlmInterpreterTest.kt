package com.voxapps.commander.domain.intent.interpreter

import android.content.Context
import com.voxapps.commander.data.local.dao.FastMapDao
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.data.remote.LlamaEngineManager
import com.voxapps.commander.data.remote.ModelDownloader
import com.voxapps.commander.testutil.TestDataFactory
import com.voxapps.llamacpp.LibLlama
import com.voxapps.llamacpp.LlamaBridge
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Collections

/**
 * [LocalLlmInterpreter] against a fake [LlamaBridge] — the contract the llama.cpp backend must
 * keep, runnable on the JVM because the bridge is the seam:
 *
 *  - the Mutex serializes native access (one model load under a concurrent burst — the crash
 *    NativeCrashReproductionTest reproduces on-device);
 *  - the grammar is built and actually handed to the sampler on every NLU call, never silently
 *    dropped (free-text output that *sometimes* parses is the worst failure shape);
 *  - preload loads the model once and pays the prefill once;
 *  - a cancelled native call surfaces as "no intent", not a crash or a hang.
 */
class LocalLlmInterpreterTest {

    /** Records calls; behaviour is adjustable per test. */
    private class FakeBridge : LlamaBridge {
        val loadCalls = Collections.synchronizedList(mutableListOf<String>())
        val completeCalls = Collections.synchronizedList(mutableListOf<Triple<String, String, String>>())
        val completeSlots = Collections.synchronizedList(mutableListOf<Int>())
        var freeCount = 0
        var response: String? =
            """{"action":"play","domain":"audio","logical_subject":"Scorpions","confidence":0.9}"""
        var nextHandle = 1L

        val loadGpuLayers = Collections.synchronizedList(mutableListOf<Int>())
        override fun loadModel(path: String, nCtx: Int, nThreads: Int, nGpuLayers: Int): Long {
            loadCalls.add(path)
            loadGpuLayers.add(nGpuLayers)
            return nextHandle
        }
        override fun freeModel(handle: Long) { freeCount++ }
        override fun jsonSchemaToGrammar(schemaJson: String): String = "root ::= \"x\""
        override fun complete(
            handle: Long, systemPrompt: String, userText: String,
            grammarGbnf: String, maxTokens: Int, temperature: Float, slot: Int
        ): String? {
            completeCalls.add(Triple(systemPrompt, userText, grammarGbnf))
            completeSlots.add(slot)
            return response
        }
        override fun cancel(handle: Long) {}
        override fun clearMemory(handle: Long) {}
        override fun contextTokenCount(handle: Long): Int = 0
        override fun lastTimings(handle: Long): LongArray? = null
        /** Null = "no GPU device / cannot say", which the interpreter treats as no capacity
         *  objection — the fake stays out of the way unless a test sets it. */
        var gpuMemoryBytes: LongArray? = null
        override fun gpuMemory(): LongArray? = gpuMemoryBytes
    }

    private lateinit var context: Context
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var modelDownloader: ModelDownloader
    private lateinit var fastMapDao: FastMapDao
    private lateinit var libManager: LlamaEngineManager
    private lateinit var bridge: FakeBridge
    private lateinit var tempDir: File
    private lateinit var modelFile: File

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0

        mockkObject(Logger)
        every { Logger.log(any(), any()) } returns Unit

        // The loader touches System.load; the bridge seam covers inference but not loading, so
        // the loader object itself is stubbed on the JVM.
        mockkObject(LibLlama)
        every { LibLlama.load(any()) } returns true

        tempDir = File(System.getProperty("java.io.tmpdir"), "vox_llm_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        modelFile = File(tempDir, "qwen3-0.6b-q8.gguf").apply { writeText("fake model") }

        context = mockk(relaxed = true)
        settingsRepo = mockk(relaxed = true)
        modelDownloader = mockk(relaxed = true)
        fastMapDao = mockk(relaxed = true)
        libManager = mockk(relaxed = true)
        every { libManager.needsRefresh() } returns false
        bridge = FakeBridge()

        coEvery { fastMapDao.getAllRulesOnce() } returns emptyList()
        every { settingsRepo.getSettingsSnapshot() } returns TestDataFactory.createAppSettings(
            aiProcessor = "nlu_llm",
            activeIntentModelId = "qwen3-0.6b-q8"
        )
        every { modelDownloader.resolveLocalFile("qwen3-0.6b-q8", "nlu_llm") } returns modelFile
    }

    @After
    fun tearDown() = unmockkAll()

    private fun interpreter() =
        LocalLlmInterpreter(context, settingsRepo, modelDownloader, fastMapDao, bridge, libManager)

    @Test
    fun `processCommand parses the bridge's constrained output into an intent`() = runTest {
        val intent = interpreter().processCommand("play scorpions", null)

        assertNotNull(intent)
        assertEquals("audio", intent!!.domain)
        assertEquals("play", intent.action)
    }

    @Test
    fun `every NLU call hands the sampler a coupled grammar — never empty`() = runTest {
        interpreter().processCommand("play scorpions", null)

        assertEquals(1, bridge.completeCalls.size)
        val grammar = bridge.completeCalls[0].third
        assertTrue("grammar was empty — output would be unconstrained free text", grammar.isNotBlank())
        assertTrue("grammar lost the action-first domain coupling", "action-domain ::=" in grammar)
        assertTrue("grammar lost the root rule", "root ::=" in grammar)
    }

    @Test
    fun `processCommand returns null when no model is selected`() = runTest {
        every { settingsRepo.getSettingsSnapshot() } returns TestDataFactory.createAppSettings(
            aiProcessor = "nlu_llm", activeIntentModelId = null
        )

        assertNull(interpreter().processCommand("play scorpions", null))
        assertEquals(0, bridge.loadCalls.size)
    }

    @Test
    fun `processCommand returns null when the model file does not exist`() = runTest {
        modelFile.delete()

        assertNull(interpreter().processCommand("play scorpions", null))
        assertEquals(0, bridge.loadCalls.size)
    }

    @Test
    fun `a cancelled native call surfaces as no intent, not a crash`() = runTest {
        bridge.response = null

        assertNull(interpreter().processCommand("play scorpions", null))
    }

    @Test
    fun `a concurrent burst loads the model exactly once`() = runTest {
        val i = interpreter()
        (1..3).map { async { i.processCommand("play scorpions", null) } }.awaitAll()

        assertEquals(1, bridge.loadCalls.size)
    }

    @Test
    fun `preload loads the model once and pays the prefill once`() = runTest {
        val i = interpreter()
        assertTrue(i.preload())

        assertEquals(1, bridge.loadCalls.size)
        assertEquals(1, bridge.completeCalls.size)

        // The command after preload must reuse the loaded model, not load again.
        i.processCommand("play scorpions", null)
        assertEquals(1, bridge.loadCalls.size)
    }

    @Test
    fun `flipping the GPU toggle reloads the model on the other backend`() = runTest {
        val i = interpreter()
        i.processCommand("play scorpions", null)
        assertEquals(listOf(0), bridge.loadGpuLayers)

        every { settingsRepo.getSettingsSnapshot() } returns TestDataFactory.createAppSettings(
            aiProcessor = "nlu_llm",
            activeIntentModelId = "qwen3-0.6b-q8"
        ).copy(llamaGpuEnabled = true)
        i.processCommand("play scorpions", null)

        // Same model id, different backend: the load-state invalidation must treat the GPU wish
        // as part of what "loaded" means.
        assertEquals(2, bridge.loadCalls.size)
        assertEquals(1, bridge.freeCount)
        assertEquals(listOf(0, -1), bridge.loadGpuLayers)
    }

    @Test
    fun `a model larger than the GPU budget stays on the CPU`() = runTest {
        // 10MB of free GPU against a model the fake reports as far bigger: the wish is granted
        // only where there is room, and the refusal is a CPU load, never a failed one.
        bridge.gpuMemoryBytes = longArrayOf(10L * 1024 * 1024, 10L * 1024 * 1024)
        modelFile.writeBytes(ByteArray(20 * 1024 * 1024))
        every { settingsRepo.getSettingsSnapshot() } returns TestDataFactory.createAppSettings(
            aiProcessor = "nlu_llm",
            activeIntentModelId = "qwen3-0.6b-q8"
        ).copy(llamaGpuEnabled = true)

        val i = interpreter()
        i.processCommand("play scorpions", null)

        assertEquals(listOf(0), bridge.loadGpuLayers)
        assertTrue(i.lastGpuSkipReason!!.contains("larger than"))
    }

    @Test
    fun `a model that fits is offloaded`() = runTest {
        bridge.gpuMemoryBytes = longArrayOf(4L * 1024 * 1024 * 1024, 4L * 1024 * 1024 * 1024)
        every { settingsRepo.getSettingsSnapshot() } returns TestDataFactory.createAppSettings(
            aiProcessor = "nlu_llm",
            activeIntentModelId = "qwen3-0.6b-q8"
        ).copy(llamaGpuEnabled = true)

        val i = interpreter()
        i.processCommand("play scorpions", null)

        assertEquals(listOf(-1), bridge.loadGpuLayers)
        assertNull(i.lastGpuSkipReason)
    }

    @Test
    fun `an incompatible verdict overrides the GPU wish`() = runTest {
        every { settingsRepo.getSettingsSnapshot() } returns TestDataFactory.createAppSettings(
            aiProcessor = "nlu_llm",
            activeIntentModelId = "qwen3-0.6b-q8"
        ).copy(llamaGpuEnabled = true, llamaGpuIncompatible = true)

        interpreter().processCommand("play scorpions", null)

        assertEquals(listOf(0), bridge.loadGpuLayers)
    }

    @Test
    fun `a different selection swaps the model`() = runTest {
        val other = File(tempDir, "other.gguf").apply { writeText("fake model 2") }
        every { modelDownloader.resolveLocalFile("other", "nlu_llm") } returns other

        val i = interpreter()
        i.processCommand("play scorpions", null)
        i.processCommand(
            "play scorpions", null,
            com.voxapps.commander.domain.engine.EngineSelection("nlu_llm", "other")
        )

        assertEquals(listOf(modelFile.absolutePath, other.absolutePath), bridge.loadCalls)
        assertEquals(1, bridge.freeCount)
    }

    @Test
    fun `rawPrompt runs unconstrained with no system framing`() = runTest {
        bridge.response = "free text answer"
        val out = interpreter().rawPrompt("summarize this", null)

        assertEquals("free text answer", out)
        val (sys, _, grammar) = bridge.completeCalls[0]
        assertEquals("", sys)
        assertEquals("", grammar)
    }

    @Test
    fun `NLU and raw prompts run in separate KV slots`() = runTest {
        val i = interpreter()
        i.processCommand("play scorpions", null)
        bridge.response = "free text answer"
        i.rawPrompt("summarize this", null)

        // A shared slot would mean each kind of call evicts the other's cached prompt prefix.
        assertEquals(
            listOf(LlamaBridge.SLOT_NLU, LlamaBridge.SLOT_RAW),
            bridge.completeSlots
        )
    }

    @Test
    fun `a failed rawPrompt records why and a successful one clears it`() = runTest {
        val i = interpreter()
        bridge.response = null
        assertNull(i.rawPrompt("summarize this", null))
        assertTrue(
            "expected a busy reason, got: ${i.lastErrorReason}",
            i.lastErrorReason!!.contains("busy")
        )

        bridge.response = "free text answer"
        assertEquals("free text answer", i.rawPrompt("summarize this", null))
        assertNull(i.lastErrorReason)
    }

    @Test
    fun `rawPrompt with no selected model reports that, not busyness`() = runTest {
        every { settingsRepo.getSettingsSnapshot() } returns TestDataFactory.createAppSettings(
            aiProcessor = "nlu_llm", activeIntentModelId = null
        )

        val i = interpreter()
        assertNull(i.rawPrompt("summarize this", null))
        assertEquals("no local model selected", i.lastErrorReason)
    }

    @Test
    fun `the system prompt is identical across different commands — the cacheable prefix`() = runTest {
        val i = interpreter()
        i.processCommand("play scorpions on spotify", null)
        i.processCommand("turn on the flashlight", null)

        assertEquals(2, bridge.completeCalls.size)
        val (sysA, userA, _) = bridge.completeCalls[0]
        val (sysB, userB, _) = bridge.completeCalls[1]
        // A prompt that varied with the utterance would diverge early and repay most of the
        // prefill every call; the static prefix is the whole point of the bridge's KV reuse.
        assertEquals(sysA, sysB)
        assertTrue(userA != userB)
        assertTrue(userA.contains("play scorpions on spotify"))
        assertTrue(userB.contains("turn on the flashlight"))
    }
}
