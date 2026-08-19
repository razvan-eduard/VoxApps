package com.voxapps.commander.domain.intent.handler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * On the collapsed path, Commander is the only one who knows what the model was asked about: the
 * satellite handed over a template and Commander filled it from its own decomposition. So the reply
 * has to carry that text back, or the satellite can never check an answer against its question.
 *
 * Checked against the source rather than by running the handler, which needs a live Context, the
 * container and a broadcast: what matters is that the text put *into* the prompt is the same text
 * sent back, and sameness of an expression is a property of the code.
 */
class ExtractionPassEchoesItsInputTest {

    private fun source(): String =
        listOf(
            "src/main/java/com/voxapps/commander/domain/intent/handler/SatelliteHandler.kt",
            "vox-commander/src/main/java/com/voxapps/commander/domain/intent/handler/SatelliteHandler.kt"
        ).map(::File).first { it.exists() }.readText()
            .lineSequence()
            .filterNot { line ->
                val t = line.trim()
                t.startsWith("import ") || t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
            .joinToString("\n")

    /** Composed once and named, rather than built inline — an inline call could not be echoed. */
    @Test
    fun `the decomposition is held in a variable`() {
        val text = source()
        assertTrue(
            "the input has to be nameable to be echoed",
            text.contains("val input = intent.toDecompositionText()")
        )
        assertTrue("and that name is what the prompt is built from", text.contains("schema.buildPrompt(input)"))
    }

    /**
     * Both replies carry it. An error reply is still an answer to something, and a satellite that
     * only learned its own question on success would have to handle two shapes of the same event.
     */
    @Test
    fun `every reply echoes it`() {
        val text = source()
        assertEquals(
            "success and error both, and nothing else invents an input",
            2,
            Regex("""\binput = input\b""").findAll(text).count()
        )
    }
}
