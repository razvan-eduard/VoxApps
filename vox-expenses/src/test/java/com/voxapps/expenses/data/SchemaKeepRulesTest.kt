package com.voxapps.expenses.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every schema this app hands to `RemoteSchema` must be kept from R8.
 *
 * Gson fills these by reflection, so a stripped field is invisible everywhere it is cheap to look:
 * the code compiles, the unit tests pass (they run without R8), and only the release build reads a
 * schema with no fields in it and refuses the download. This app hit exactly that — the file was
 * served correctly and the log said "did not parse, or arrived empty" — after a hand-rolled
 * org.json reader was replaced with Gson and nobody added the keep rule.
 *
 * So rather than assert the rules that exist, this asserts they *cover* what the code parses: the
 * types passed to RemoteSchema, read from the source itself.
 */
class SchemaKeepRulesTest {

    private fun repoFile(relative: String): File =
        listOf(File(relative), File("../$relative"), File("vox-expenses/$relative"))
            .firstOrNull { it.exists() }
            ?: error("$relative not found from ${File(".").absolutePath}")

    @Test
    fun `every schema type handed to RemoteSchema is kept from R8`() {
        val sources = repoFile("src/main/java").walkTopDown().filter { it.extension == "kt" }
        val parsedTypes = sources
            .flatMap { file ->
                Regex("""type\s*=\s*(\w+)::class\.java""").findAll(file.readText()).map { it.groupValues[1] }
            }
            .toSet()

        assertTrue("no RemoteSchema usage found — has the loader been renamed?", parsedTypes.isNotEmpty())

        val rules = repoFile("proguard-rules.pro").readText()
        val unkept = parsedTypes.filterNot { type -> rules.contains(".$type ") || rules.contains(".$type{") }

        assertTrue(
            "these types are parsed by Gson but not kept, so R8 will strip their fields in " +
                "release: $unkept",
            unkept.isEmpty()
        )
    }
}
