package com.voxapps.docread

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every schema this module hands to `RemoteSchema` must be kept from R8.
 *
 * Gson fills these by reflection, so a stripped field is invisible everywhere it is cheap to look:
 * the code compiles, the unit tests pass — they run without R8 — and only the release build reads a
 * schema with no fields in it and refuses the download. This repository has already paid for that
 * once, in an app, from a device log.
 *
 * The rules ship as the module's own consumer rules rather than being repeated in each consuming
 * app, since it is this module that decides what it parses; a consumer cannot be expected to know.
 * So this asserts they *cover* what the code parses, read from the source itself, rather than
 * asserting the rules that happen to exist.
 */
class SchemaKeepRulesTest {

    private fun repoFile(relative: String): File =
        listOf(File(relative), File("../$relative"), File("core/docread/$relative"))
            .firstOrNull { it.exists() }
            ?: error("$relative not found from ${File(".").absolutePath}")

    @Test
    fun `every schema type handed to RemoteSchema is kept from R8`() {
        val parsedTypes = repoFile("src/main/java").walkTopDown()
            .filter { it.extension == "kt" }
            .flatMap { file ->
                Regex("""type\s*=\s*(\w+)::class\.java""").findAll(file.readText()).map { it.groupValues[1] }
            }
            .toSet()

        assertTrue("no RemoteSchema usage found — has the loader been renamed?", parsedTypes.isNotEmpty())

        val rules = repoFile("consumer-rules.pro").readText()
        val unkept = parsedTypes.filterNot { rules.contains(".$it ") || rules.contains(".$it{") }

        assertTrue(
            "these types are parsed by Gson but not kept, so R8 will strip their fields in the " +
                "apps that use this module: $unkept",
            unkept.isEmpty()
        )
    }

    /**
     * The nested entries are filled by reflection too, and are not named at any `type =` site, so
     * nothing above would notice them going missing.
     */
    @Test
    fun `the entry types the schema is made of are kept as well`() {
        val rules = repoFile("consumer-rules.pro").readText()

        for (type in listOf("ColumnTemplateEntry", "HeaderTemplateEntry", "ItemTemplateEntry", "FooterTemplateEntry")) {
            assertTrue("$type is parsed by Gson but not kept", rules.contains(".$type "))
        }
    }
}
