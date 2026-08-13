package com.voxapps.commander

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.voxapps.identity.VoxRepo
import com.voxapps.llamacpp.LibLlama
import com.voxapps.llamacpp.LlamaBridgeImpl
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The llama.cpp bridge exercised end to end on a real runtime — native load, model load, and the
 * grammar sentinel. Runs in the weekly instrumented job (x86_64 API-30 emulator executing the
 * arm64 library under binary translation), which is also what answers whether libllama.so
 * survives translation at all — a fault here scopes the test out the way vision's OpenCV was,
 * with prose in the workflow header and a real-arm64 command.
 *
 * The model is a ~1.2 MB test asset (stories260K) published beside the real NLU models: a real
 * gguf the sampler genuinely runs, small enough for CI to fetch per run. The grammar sentinel
 * `root ::= "XOK"` is the attachment proof — no free-running model emits exactly XOK by chance,
 * so any other output means the grammar was built but never handed to the sampler (the
 * green-but-doing-nothing failure this test exists to keep red).
 */
@RunWith(AndroidJUnit4::class)
class LlamaBridgeSmokeTest {

    @Test
    fun grammarSentinel_endToEnd() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // Debug builds bundle libllama.so in the APK, so the system loader serves it.
        assertTrue("libllama.so failed to load", LibLlama.load(null))

        val model = File(context.cacheDir, "stories260K.gguf")
        if (!model.exists() || model.length() == 0L) {
            val url = VoxRepo.RELEASE_DOWNLOAD_BASE + "nlu-assets/stories260K.gguf"
            java.net.URL(url).openStream().use { input ->
                model.outputStream().use { input.copyTo(it) }
            }
        }
        assertTrue("test model missing", model.length() > 0)

        val handle = LlamaBridgeImpl.loadModel(model.absolutePath, nCtx = 512, nThreads = 2)
        try {
            val out = LlamaBridgeImpl.complete(
                handle,
                systemPrompt = "",
                userText = "Once upon a time",
                grammarGbnf = "root ::= \"XOK\"",
                maxTokens = 8,
                temperature = 0.1f
            )
            assertEquals("grammar was not attached to the sampler", "XOK", out)

            assertTrue("nothing resident after a completion", LlamaBridgeImpl.contextTokenCount(handle) > 0)

            // Slot isolation: a raw-slot completion must not evict slot 0's resident prefix, and
            // a constrained completion must still answer correctly after alternating. The token
            // count strictly growing across the raw call is the eviction check — under one
            // sequence the raw call would first drop slot 0's tokens.
            val residentAfterNlu = LlamaBridgeImpl.contextTokenCount(handle)
            LlamaBridgeImpl.complete(
                handle,
                systemPrompt = "",
                userText = "The little dog",
                grammarGbnf = "",
                maxTokens = 4,
                temperature = 0.1f,
                slot = com.voxapps.llamacpp.LlamaBridge.SLOT_RAW
            )
            assertTrue(
                "raw-slot call evicted the NLU slot's resident tokens",
                LlamaBridgeImpl.contextTokenCount(handle) > residentAfterNlu
            )
            val outAfterAlternation = LlamaBridgeImpl.complete(
                handle,
                systemPrompt = "",
                userText = "Once upon a time",
                grammarGbnf = "root ::= \"XOK\"",
                maxTokens = 8,
                temperature = 0.1f
            )
            assertEquals("constrained output wrong after slot alternation", "XOK", outAfterAlternation)

            // A prompt past half the context must still decode. Asking for more than one KV
            // sequence divides the context between them unless the pool is unified, which silently
            // halves what any single call can hold — and the size guard, measuring the context
            // total rather than what a sequence actually gets, let such a prompt through to fail
            // inside the decode with nothing said about why. Sized past half and well inside the
            // whole, so it passes only when the pool is shared.
            // 40 repetitions, not more: the test model's tiny vocabulary tokenizes prose at
            // nearly two tokens per word, so the count is chosen against measured tokens (~370
            // here) — past the 256 a divided pool would allow, safely under the 512 whole.
            val longPrompt = (1..40).joinToString(" ") { "the little dog ran home" }
            val longOut = LlamaBridgeImpl.complete(
                handle,
                systemPrompt = "",
                userText = longPrompt,
                grammarGbnf = "root ::= \"XOK\"",
                maxTokens = 4,
                temperature = 0.1f,
                slot = com.voxapps.llamacpp.LlamaBridge.SLOT_RAW
            )
            assertEquals("a prompt past half the context failed to decode", "XOK", longOut)

            LlamaBridgeImpl.clearMemory(handle)
            assertEquals("clearMemory left tokens resident", 0, LlamaBridgeImpl.contextTokenCount(handle))

            // The capacity query the offload decision depends on. Null is a legitimate answer
            // (no GPU device), but a *thrown* one is not: the symbol has to bind, or the
            // interpreter's fail-open turns the whole capacity check into a silent no-op — which
            // is exactly how it shipped inert once.
            val mem = LlamaBridgeImpl.gpuMemory()
            if (mem != null) {
                assertEquals("gpuMemory should report free and total", 2, mem.size)
                assertTrue("total GPU memory should be positive when reported", mem[1] >= 0)
            }
        } finally {
            LlamaBridgeImpl.freeModel(handle)
        }
    }
}
