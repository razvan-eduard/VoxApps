package com.voxcommander.app.data.remote

import android.util.Log
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Regression test for the Vosk "Failed to create a model" bug: the archive's single top-level
 * wrapper dir was flattened with File.copyTo, which is non-recursive for directories, leaving
 * subdirectories (am/ conf/ graph/ ivector/) empty. This reproduces that exact layout and
 * asserts the fixed flatten preserves nested files.
 */
class ModelDownloaderFlattenTest {

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        mockkObject(com.voxcommander.app.utils.Logger)
        every { com.voxcommander.app.utils.Logger.log(any(), any()) } returns Unit
    }

    @Test
    fun `flatten moves nested subdirectory files up one level`() {
        val targetDir = Files.createTempDirectory("vosk-model").toFile()
        try {
            // Simulate raw extraction: a single wrapper dir containing a top-level file AND
            // subdirectories with files inside (the case the old copyTo silently dropped).
            val wrapper = File(targetDir, "vosk-model-small-en-us-0.15").apply { mkdirs() }
            File(wrapper, "README").writeText("model readme")
            File(wrapper, "am").apply { mkdirs() }.let { File(it, "final.mdl").writeText("MDL-BYTES") }
            File(wrapper, "conf").apply { mkdirs() }.let { File(it, "model.conf").writeText("conf") }
            File(wrapper, "graph").apply { mkdirs() }.let { File(it, "HCLG.fst").writeText("FST") }

            ModelDownloader.flattenNestedDir(targetDir)

            // Wrapper is gone; files sit directly under targetDir.
            assertFalse("wrapper dir should be removed", wrapper.exists())
            assertTrue("README should be present", File(targetDir, "README").exists())

            val amFile = File(targetDir, "am/final.mdl")
            assertTrue("am/final.mdl must exist after flatten", amFile.exists())
            assertTrue("am/final.mdl must be non-empty", amFile.length() > 0)
            assertEquals("MDL-BYTES", amFile.readText())

            assertTrue("conf/model.conf must exist", File(targetDir, "conf/model.conf").exists())
            assertTrue("graph/HCLG.fst must exist", File(targetDir, "graph/HCLG.fst").exists())
        } finally {
            targetDir.deleteRecursively()
        }
    }

    @Test
    fun `flatten is a no-op when there is no single wrapper dir`() {
        val targetDir = Files.createTempDirectory("flat-model").toFile()
        try {
            // Already-flat layout: am/ directly under targetDir plus a sibling file.
            File(targetDir, "am").apply { mkdirs() }.let { File(it, "final.mdl").writeText("x") }
            File(targetDir, "README").writeText("r")

            ModelDownloader.flattenNestedDir(targetDir)

            assertTrue(File(targetDir, "am/final.mdl").exists())
            assertTrue(File(targetDir, "README").exists())
        } finally {
            targetDir.deleteRecursively()
        }
    }
}
