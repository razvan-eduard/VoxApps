package com.voxapps.commander.data.remote

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.reflect.full.primaryConstructor

/**
 * The one rule every Gson-parsed class has to obey, checked against the classes themselves.
 *
 * Gson fills objects by reflection, and how it *makes* them depends on something invisible: if every
 * constructor parameter has a default, Kotlin emits a no-arg constructor and Gson uses it, so an
 * absent field takes its default. A single parameter without one and there is no such constructor —
 * Gson then allocates the object without running any constructor at all, and *every* absent field
 * arrives null, including the ones typed as non-null and the ones with perfectly good defaults.
 *
 * The failure is silent, distant, and nothing like its cause: the app crashes iterating a "non-null"
 * list, in a class whose declaration reads as if it could not be empty. It has happened twice here —
 * an import missing `searchProviderApiKeys`, and a search category omitting `providers`.
 *
 * These classes are what R8 is told to keep, which is the same list for the same reason: they are
 * the ones built from JSON. Read from the rules file rather than restated, so a class added there
 * is covered here without anyone remembering to add it twice.
 */
class GsonDtoContractTest {

    private fun repoFile(relative: String): File =
        listOf(File(relative), File("../$relative"), File("vox-commander/$relative"))
            .firstOrNull { it.exists() }
            ?: error("$relative not found from ${File(".").absolutePath}")

    /** Class names from `-keep class com.voxapps…` lines, in the form Class.forName wants. */
    private fun keptClassNames(): List<String> =
        repoFile("proguard-rules.pro").readLines()
            .mapNotNull { line ->
                Regex("""^-keep class (com\.voxapps\.[\w.$]+)""").find(line.trim())?.groupValues?.get(1)
            }
            .distinct()

    @Test
    fun `every class R8 keeps for Gson can be built through its constructor`() {
        val kept = keptClassNames()
        assertTrue("no -keep class rules found", kept.isNotEmpty())

        val offenders = kept.mapNotNull { name ->
            // A class the unit-test classpath cannot see (Android-only supertypes) is skipped rather
            // than failed: this test is about the shape of the declaration, not about loading it.
            val type = runCatching { Class.forName(name).kotlin }.getOrNull() ?: return@mapNotNull null
            val constructor = runCatching { type.primaryConstructor }.getOrNull() ?: return@mapNotNull null

            val required = constructor.parameters.filterNot { it.isOptional }.mapNotNull { it.name }
            if (required.isEmpty()) null else "$name → $required"
        }

        assertTrue(
            "these Gson-parsed classes have parameters with no default, so Gson will skip their " +
                "constructor and null every absent field:\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }
}
