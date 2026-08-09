package com.voxapps.commander.data.remote

import android.util.Log
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Covers the single answer to "where is the loadable artefact".
 *
 * Both the download validator and the engine go through this, and the validator *deletes* what it
 * rejects — so a wrong answer here does not merely fail to load a model, it erases one. That is
 * exactly what happened when the validator looked for a fixed `model.onnx` while Piper archives ship
 * the weights named after the voice.
 */
class ModelEntryPointTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        mockkObject(com.voxapps.logging.Logger)
        every { com.voxapps.logging.Logger.log(any(), any()) } returns Unit
    }

    // --- self: the downloaded file is the artefact -------------------------------------------

    @Test
    fun `self resolves to the downloaded file itself`() {
        val file = tmp.newFile("base.en.bin")

        assertEquals(file, ModelDownloader.resolveEntry(file, EntryPoint(self = true)))
    }

    @Test
    fun `self rejects a directory`() {
        // A single-file engine whose path resolved to a directory means the layout rules disagree
        // somewhere upstream; handing that to whisper.cpp fails natively.
        val dir = tmp.newFolder("base.en.bin")

        assertNull(ModelDownloader.resolveEntry(dir, EntryPoint(self = true)))
    }

    // --- target file: the match itself (Piper) -------------------------------------------------

    @Test
    fun `target file resolves weights named after the voice`() {
        val dir = tmp.newFolder("vits-piper-en_US-lessac-medium")
        File(dir, "en_US-lessac-medium.onnx").writeText("w")
        File(dir, "en_US-lessac-medium.onnx.json").writeText("{}")
        File(dir, "tokens.txt").writeText("t")

        val resolved = ModelDownloader.resolveEntry(dir, EntryPoint(match = "*.onnx", target = "file"))

        assertEquals("en_US-lessac-medium.onnx", resolved?.name)
    }

    @Test
    fun `the sidecar json is not mistaken for the weights`() {
        // "*.onnx" must not match "x.onnx.json" — a substring test would, and sherpa-onnx handed a
        // config file as its model fails on the native side.
        val dir = tmp.newFolder("json-only")
        File(dir, "en_US-lessac-medium.onnx.json").writeText("{}")

        assertNull(ModelDownloader.resolveEntry(dir, EntryPoint(match = "*.onnx", target = "file")))
    }

    // --- target dir: the directory containing the match (Vosk) ---------------------------------

    @Test
    fun `target dir resolves to the directory holding the marker`() {
        val dir = tmp.newFolder("vosk-model-en-us-0.22")
        File(dir, "am").mkdirs()
        File(dir, "conf").mkdirs()

        val resolved = ModelDownloader.resolveEntry(dir, EntryPoint(match = "am", target = "dir"))

        assertEquals(dir.canonicalPath, resolved?.canonicalPath)
    }

    @Test
    fun `a wrapper directory resolves without moving any file`() {
        // Published archives sometimes nest everything one level deeper. Searching for the marker
        // handles that; the old code moved files on disk to compensate.
        val dir = tmp.newFolder("vosk-model-en-us-0.22")
        val wrapper = File(dir, "vosk-model-en-us-0.22").apply { mkdirs() }
        File(wrapper, "am").mkdirs()

        val resolved = ModelDownloader.resolveEntry(dir, EntryPoint(match = "am", target = "dir"))

        assertEquals(wrapper.canonicalPath, resolved?.canonicalPath)
        assertTrue("nothing may be moved on disk", File(wrapper, "am").exists())
    }

    @Test
    fun `a shallower match wins over a deeper one`() {
        val dir = tmp.newFolder("model")
        File(dir, "am").mkdirs()
        File(dir, "extra/am").mkdirs()

        val resolved = ModelDownloader.resolveEntry(dir, EntryPoint(match = "am", target = "dir"))

        assertEquals(dir.canonicalPath, resolved?.canonicalPath)
    }

    // --- failure modes -------------------------------------------------------------------------

    @Test
    fun `a missing marker resolves to null and deletes nothing`() {
        val dir = tmp.newFolder("incomplete")
        File(dir, "README").writeText("only this")

        assertNull(ModelDownloader.resolveEntry(dir, EntryPoint(match = "am", target = "dir")))
        assertTrue("resolution must be side-effect free", dir.exists() && File(dir, "README").exists())
    }

    @Test
    fun `an absent directory resolves to null rather than throwing`() {
        assertNull(
            ModelDownloader.resolveEntry(File(tmp.root, "absent"), EntryPoint(match = "*.onnx", target = "file"))
        )
    }

    @Test
    fun `an entry declaring neither self nor match resolves to null`() {
        assertNull(ModelDownloader.resolveEntry(tmp.newFolder("empty-decl"), EntryPoint()))
    }

    @Test
    fun `a match escaping the model directory is refused`() {
        // `match` arrives from a models.json that a user-configured modelRepoBaseUrl may serve, so it
        // is untrusted input describing a path — the same concern as zip-slip, one step later.
        val root = tmp.newFolder("model")
        File(root, "am").mkdirs()
        val outsider = File(tmp.root, "secret.onnx").apply { writeText("x") }

        val resolved = ModelDownloader.resolveEntry(root, EntryPoint(match = "../secret.onnx", target = "file"))

        assertNull(resolved)
        assertTrue("the file outside must be untouched", outsider.exists())
    }
}
