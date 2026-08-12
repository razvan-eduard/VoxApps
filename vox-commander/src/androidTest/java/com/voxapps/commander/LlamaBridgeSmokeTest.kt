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
            LlamaBridgeImpl.clearMemory(handle)
            assertEquals("clearMemory left tokens resident", 0, LlamaBridgeImpl.contextTokenCount(handle))
        } finally {
            LlamaBridgeImpl.freeModel(handle)
        }
    }
}
