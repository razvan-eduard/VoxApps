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
        var freeCount = 0
        var response: String? =
            """{"action":"play","domain":"audio","logical_subject":"Scorpions","confidence":0.9}"""
        var nextHandle = 1L

        override fun loadModel(path: String, nCtx: Int, nThreads: Int): Long {
            loadCalls.add(path)
            return nextHandle
        }
        override fun freeModel(handle: Long) { freeCount++ }
        override fun jsonSchemaToGrammar(schemaJson: String): String = "root ::= \"x\""
        override fun complete(
            handle: Long, systemPrompt: String, userText: String,
            grammarGbnf: String, maxTokens: Int, temperature: Float
        ): String? {
            completeCalls.add(Triple(systemPrompt, userText, grammarGbnf))
            return response
        }
        override fun cancel(handle: Long) {}
        override fun clearMemory(handle: Long) {}
        override fun contextTokenCount(handle: Long): Int = 0
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
}
