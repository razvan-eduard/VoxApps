package com.voxapps.commander

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The native bridge classes are reached from native code by literal name, so R8 sees no Kotlin
 * caller it must preserve the name for — and the failure stripping produces is an
 * UnsatisfiedLinkError at first use, not a build error. proguard-rules.pro is this module's only
 * defence; this pins the packages it must keep. Same net as vox-vision's KeepRulesTest.
 */
class KeepRulesTest {

    private fun repoFile(relative: String): File =
        listOf(File(relative), File("../$relative"), File("vox-commander/$relative"))
            .firstOrNull { it.exists() }
            ?: error("$relative not found from ${File(".").absolutePath}")

    @Test
    fun `the native bridge packages are kept`() {
        val rules = repoFile("proguard-rules.pro").readText()
        val required = listOf(
            "com.whispercpp.whisper.WhisperLib",
            "com.voxapps.llamacpp",
            "ai.onnxruntime",
            "com.k2fsa.sherpa.onnx"
        )

        val missing = required.filterNot { pkg ->
            Regex("""-keep[a-z,]*\s+class\s+${Regex.escape(pkg)}""").containsMatchIn(rules)
        }

        assertTrue(
            "proguard-rules.pro no longer keeps: $missing — R8 will strip their JNI bridge classes",
            missing.isEmpty()
        )
    }
}
