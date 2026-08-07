package com.voxapps.commander.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Covers how an extracted Piper voice directory is read.
 *
 * Both the engine and the download validator resolve the weights through
 * [PiperTtsEngine.findVoiceModelFile], and the validator deletes any directory it considers
 * incomplete — so a disagreement here does not merely fail to load a voice, it erases one.
 */
class PiperVoiceLayoutTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `finds weights named after the voice, which is how the archives ship them`() {
        val dir = tmp.newFolder("vits-piper-en_US-lessac-medium")
        java.io.File(dir, "en_US-lessac-medium.onnx").writeText("w")
        java.io.File(dir, "en_US-lessac-medium.onnx.json").writeText("{}")
        java.io.File(dir, "tokens.txt").writeText("t")
        java.io.File(dir, "MODEL_CARD").writeText("c")

        assertEquals("en_US-lessac-medium.onnx", PiperTtsEngine.findVoiceModelFile(dir)?.name)
    }

    @Test
    fun `still finds weights under the generic name`() {
        val dir = tmp.newFolder("generic-voice")
        java.io.File(dir, "model.onnx").writeText("w")

        assertEquals("model.onnx", PiperTtsEngine.findVoiceModelFile(dir)?.name)
    }

    @Test
    fun `the sidecar json is not mistaken for the weights`() {
        // ".onnx.json" ends with "json", not "onnx" — but a substring test would match it, and
        // handing sherpa-onnx a config file as its model produces a native-side failure.
        val dir = tmp.newFolder("json-only")
        java.io.File(dir, "en_US-lessac-medium.onnx.json").writeText("{}")

        assertNull(PiperTtsEngine.findVoiceModelFile(dir))
    }

    @Test
    fun `returns null for a directory with no weights at all`() {
        assertNull(PiperTtsEngine.findVoiceModelFile(tmp.newFolder("empty")))
    }

    @Test
    fun `returns null rather than throwing when the directory does not exist`() {
        assertNull(PiperTtsEngine.findVoiceModelFile(java.io.File(tmp.root, "absent")))
    }
}
