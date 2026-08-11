package com.voxapps.vision

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The OCR bridge classes are reached from native code and reflection, so R8 sees no Kotlin caller
 * and would strip them — and the failure that produces is an UnsatisfiedLinkError at first OCR,
 * not a build error. proguard-rules.pro is this module's only defence; this pins the packages it
 * must keep.
 */
class KeepRulesTest {

    private fun repoFile(relative: String): File =
        listOf(File(relative), File("../$relative"), File("vox-vision/$relative"))
            .firstOrNull { it.exists() }
            ?: error("$relative not found from ${File(".").absolutePath}")

    @Test
    fun `the native bridge packages are kept`() {
        val rules = repoFile("proguard-rules.pro").readText()
        val required = listOf("org.opencv", "ai.onnxruntime", "com.paddle.ocr")

        val missing = required.filterNot { pkg ->
            Regex("""-keep[a-z,]*\s+class\s+${Regex.escape(pkg)}""").containsMatchIn(rules)
        }

        assertTrue(
            "proguard-rules.pro no longer keeps: $missing — R8 will strip their JNI bridge classes",
            missing.isEmpty()
        )
    }
}
