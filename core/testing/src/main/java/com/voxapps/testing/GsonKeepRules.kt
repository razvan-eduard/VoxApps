package com.voxapps.testing

import org.junit.Assert.assertTrue
import java.io.File

/**
 * The check every module needs and none can run for another: are the classes this module parses
 * with Gson kept from R8?
 *
 * A stripped field is invisible where it is cheap to look. The code compiles, unit tests pass —
 * they run without R8 — and only the release build reads an object with no fields in it. vox-expenses
 * hit exactly that: a schema served correctly, refused on the device with "did not parse, or arrived
 * empty", because a hand-rolled org.json reader had been replaced with Gson and nobody added a rule.
 *
 * So this asserts *coverage* rather than the presence of some rules: it reads the module's own
 * source for the types handed to Gson, then checks the module's proguard file keeps each one.
 */
object GsonKeepRules {

    /**
     * Every `X::class.java` and `TypeToken<X>` in the module.
     *
     * Deliberately not trying to recognise *which* call is a Gson call: the shapes vary
     * (`fromJson`, a `RemoteSchema` constructor argument, a round-trip helper), and a pattern that
     * misses one is worse than useless — it reports a clean bill of health for a module it never
     * looked at properly, which is what a narrower version of this did.
     *
     * The filter is applied afterwards, on what the type *is* rather than where it appears.
     */
    private val CLASS_LITERALS = listOf(
        Regex("""(\w+)::class\.java"""),
        Regex("""TypeToken<\s*(\w+)\s*>""")
    )

    private val DATA_CLASS = Regex("""data class (\w+)""")
    private val ENCLOSING = Regex("""^(?:internal |private )?(?:object|class) (\w+)""", RegexOption.MULTILINE)

    /** Types the JVM and Kotlin provide, which R8 is not about to strip out from under us. */
    private val BUILT_IN = setOf(
        "Map", "List", "Set", "String", "Int", "Long", "Float", "Double", "Boolean", "Array", "Any",
        "MutableMap", "MutableList", "JsonObject", "JsonElement", "T"
    )

    /**
     * Fails when [moduleDir] parses a type its proguard rules do not keep.
     *
     * [moduleDir] is the module root — the folder holding `src/` and `proguard-rules.pro`.
     */
    fun assertParsedTypesAreKept(moduleDir: File) {
        val sources = File(moduleDir, "src/main").walkTopDown()
            .filter { it.extension == "kt" }
            .toList()

        // What this module declares as a data class — the shape a JSON payload is parsed into.
        // Activities, services and databases also appear as class literals and are kept by other
        // means; they are not what Gson fills in field by field.
        val dataClasses = sources
            .flatMap { file -> DATA_CLASS.findAll(file.readText()).map { it.groupValues[1] } }
            .toSet()

        val parsed = sources
            .flatMap { file ->
                val text = file.readText()
                CLASS_LITERALS.flatMap { pattern -> pattern.findAll(text).map { it.groupValues[1] }.toList() }
            }
            .filterNot { it in BUILT_IN }
            .filter { it in dataClasses }
            .toSet()

        if (parsed.isEmpty()) return

        // Fully qualified, because a keep rule matches a package too — and a rule for one package
        // says nothing about a class in another. An earlier version of this checked whether the
        // rules file merely *contained* a wildcard anywhere, which passed every module for free.
        val packageOf = sources.associate { file ->
            file to PACKAGE.find(file.readText())?.groupValues?.get(1).orEmpty()
        }
        val qualified = parsed.associateWith { type ->
            val declaring = sources.firstOrNull { DATA_CLASS_OF(type).containsMatchIn(it.readText()) }
            val pkg = declaring?.let { packageOf[it] }.orEmpty()
            // A nested declaration is kept as Outer$Inner, which is a different name from
            // package.Inner — and reporting the latter as unkept is a false alarm about a rule that
            // is right there. The enclosing object is whatever was declared last before it.
            val text = declaring?.readText().orEmpty()
            val enclosing = ENCLOSING.findAll(text.substringBefore("data class $type"))
                .lastOrNull()?.groupValues?.get(1)
            val simple = if (enclosing != null) "$enclosing\$$type" else type
            if (pkg.isEmpty()) simple else "$pkg.$simple"
        }

        val patterns = File(moduleDir, "proguard-rules.pro").takeIf { it.exists() }
            ?.readLines()
            ?.mapNotNull { KEEP_RULE.find(it.trim())?.groupValues?.get(1) }
            ?.map { it.toKeepRegex() }
            .orEmpty()

        val unkept = qualified.filterValues { name -> patterns.none { it.matches(name) } }.keys

        assertTrue(
            "these types are parsed by Gson in ${moduleDir.name} but no proguard rule keeps them, " +
                "so R8 will strip their fields in a release build: $unkept",
            unkept.isEmpty()
        )
    }

    private val PACKAGE = Regex("""^package ([\w.]+)""", RegexOption.MULTILINE)
    private val KEEP_RULE = Regex("""^-keep(?:\s+\w+)*\s+class\s+([\w.*$]+)""")

    private fun DATA_CLASS_OF(type: String) = Regex("""data class $type[\s(]""")

    /** ProGuard's globs: `**` spans packages, `*` stops at a dot. */
    private fun String.toKeepRegex(): Regex =
        Regex(
            Regex.escape(this)
                .replace("**", """\E.*\Q""")
                .replace(Regex("""(?<!\.)\*(?!\*)"""), """\E[^.]*\Q""")
                .let { "$it(\\\$.*)?" }
        )

    /** Finds a module root from whichever directory the test happens to run in. */
    fun moduleDir(name: String): File =
        listOf(File("."), File(".."), File("../..")).map { File(it, name) }
            .firstOrNull { File(it, "proguard-rules.pro").exists() || File(it, "src/main").exists() }
            ?: error("module $name not found from ${File(".").absolutePath}")
}
