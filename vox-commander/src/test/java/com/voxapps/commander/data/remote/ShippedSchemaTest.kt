package com.voxapps.commander.data.remote

import com.google.gson.Gson
import com.voxapps.commander.domain.engine.AndroidTtsEngine
import com.voxapps.commander.utils.Strings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Checks the *shipped* assets against the code that reads them.
 *
 * The compiler cannot see inside a JSON file, so everything the schema promises is unverified until
 * something asserts it — and the two ways this has already gone wrong were both invisible to a green
 * test suite: a field added to the schema and stripped by R8 in release only, and a remote copy
 * whose version made the app silently prefer it. These tests are cheap and they run against the real
 * files rather than fixtures, which is the entire point.
 */
class ShippedSchemaTest {

    private val gson = Gson()

    /** Tests run from either the module directory or the repository root depending on the invoker. */
    private fun repoFile(relative: String): File =
        listOf(File(relative), File("../$relative"), File("vox-commander/$relative"))
            .firstOrNull { it.exists() }
            ?: error("$relative not found from ${File(".").absolutePath}")

    /**
     * Normalised exactly as the app normalises it on load. Gson leaves a collection the JSON omits
     * null behind a non-null Kotlin type, so a schema read raw is not the schema the app runs — and
     * asserting against the raw one would either fail spuriously or, worse, pass while the app
     * crashed on the same file.
     */
    private fun parse(file: File): RemoteModelSchema =
        RemoteModelRegistry.normalised(gson.fromJson(file.readText(), RemoteModelSchema::class.java))

    private fun assetModels() = parse(repoFile("src/main/assets/models.json"))
    private fun assetVirtual() = parse(repoFile("src/main/assets/virtual_models.json"))

    private fun translations(): Map<*, *> =
        gson.fromJson(repoFile("src/main/assets/translations/en.json").readText(), Map::class.java)

    @Test
    fun `every declared runtime is one the code knows`() {
        (assetModels().engines + assetVirtual().engines).forEach { (key, config) ->
            assertNotNull(
                "engine '$key' declares runtime '${config.runtime}', which EngineRuntime cannot parse",
                EngineRuntime.fromKey(config.runtime)
            )
        }
    }

    @Test
    fun `a local_file engine says what it downloads and where the artefact lands`() {
        assetModels().engines
            .filter { EngineRuntime.fromKey(it.value.runtime) == EngineRuntime.LOCAL_FILE }
            .forEach { (key, config) ->
                assertTrue("local_file engine '$key' declares no extension", config.extension.isNotBlank())
                assertNotNull("local_file engine '$key' declares no entry point", config.entry)
            }
    }

    /**
     * A virtual engine that looked downloadable would be offered a download button, counted as the
     * whisper or vosk engine by the by-packaging lookups, and asked for an entry point it has no
     * file to resolve.
     */
    @Test
    fun `a virtual engine downloads nothing and claims no packaging`() {
        assetVirtual().engines.forEach { (key, config) ->
            assertTrue(
                "virtual engine '$key' declares runtime '${config.runtime}'",
                EngineRuntime.fromKey(config.runtime) != EngineRuntime.LOCAL_FILE
            )
            assertEquals("virtual engine '$key' declares an extension", "", config.extension)
            assertNull("virtual engine '$key' declares an entry point", config.entry)
            assertTrue("virtual engine '$key' ships models", config.models.isEmpty())
        }
    }

    /**
     * `local_llm` is what makes the app try to load a model file through the on-device interpreter.
     * A cloud engine claiming it would be handed a path that does not exist.
     */
    @Test
    fun `no virtual engine claims to be an on-device LLM`() {
        assetVirtual().engines.forEach { (key, config) ->
            assertTrue("virtual engine '$key' declares local_llm", "local_llm" !in config.capabilities)
        }
    }

    /**
     * The failure this caught: three virtual engines declare no capabilities at all, and reading
     * that field threw. Gson instantiates without the Kotlin constructor, so *every* field the JSON
     * omits is null regardless of what its type says — including `extension`, which fails inside
     * `copy` before any reader gets near it.
     */
    @Test
    fun `an engine that declares almost nothing is usable anyway`() {
        val raw = gson.fromJson(
            """{"schema_version":1,"engines":{"bare":{"is_multilingual":false}}}""",
            RemoteModelSchema::class.java
        )

        val engine = RemoteModelRegistry.normalised(raw).engines.getValue("bare")

        assertEquals("", engine.extension)
        assertTrue(engine.capabilities.isEmpty())
        assertTrue(engine.type.isEmpty())
        assertTrue(engine.models.isEmpty())
    }

    @Test
    fun `the two files describe disjoint sets of engines`() {
        val overlap = assetModels().engines.keys intersect assetVirtual().engines.keys
        assertEquals("an engine is declared in both files", emptySet<String>(), overlap)
    }

    /**
     * The keys are the values already written to settings and to every backup ever exported. A
     * rename here does not migrate anything — it silently unselects the user's engine.
     */
    @Test
    fun `virtual engine keys are spelled exactly as the stored settings values`() {
        val declared = assetVirtual().engines.keys

        setOf(
            Strings.Processors.GOOGLE,
            Strings.Processors.WHISPER_API,
            Strings.AiProcessors.OPENAI,
            Strings.AiProcessors.GEMINI_CLOUD,
            Strings.AiProcessors.GEMINI_NATIVE,
            AndroidTtsEngine.ENGINE_KEY
        ).forEach {
            assertTrue("stored processor '$it' is not declared", it in declared)
        }
    }

    @Test
    fun `every declared translations key resolves in English`() {
        val strings = translations()
        (assetModels().engines + assetVirtual().engines).forEach { (engine, config) ->
            listOfNotNull(config.label_key, config.api_key_help_key).forEach {
                assertTrue("engine '$engine' declares '$it', which en.json lacks", strings.containsKey(it))
            }
        }
    }

    /**
     * An engine that needs a credential but cannot say where to get one leaves the user at a blank
     * field. Not fatal, so not an error — but the shipped engines should all answer it.
     */
    @Test
    fun `an engine that needs a key says where to get one`() {
        (assetModels().engines + assetVirtual().engines)
            .filter { "requires_api_key" in it.value.capabilities }
            .forEach { (engine, config) ->
                assertTrue(
                    "engine '$engine' requires a key but declares neither api_key_url nor api_key_help_key",
                    config.api_key_url != null || config.api_key_help_key != null
                )
            }
    }

    /**
     * Every wake-word engine the schema describes has a class behind it, and every class is
     * reachable from the schema.
     *
     * The compiler cannot check a JSON file, so without this an engine can be listed, offered in the
     * picker, selected — and then fail to build at the moment the service starts. The reverse gap is
     * quieter still: an implementation nothing can select looks like working code forever.
     */
    @Test
    fun `wake word engines and their implementations agree`() {
        val declared = assetModels().engines
            .filter { "wake_word" in it.value.type }
            .keys

        assertEquals(declared, com.voxapps.commander.service.WakeWordEngines.supportedKeys)
    }

    /**
     * Porcupine's keywords are compiled into the library, so `models.json` can only ever *name*
     * them — and a name the SDK does not recognise is a model the user can select and never
     * trigger. The hand-written map that used to sit between the two is gone; this is what keeps
     * the remaining two honest.
     */
    @Test
    fun `every Porcupine model names a keyword the SDK actually has`() {
        val declared = assetModels().engines["wake_porcupine"]?.models.orEmpty()
        assertTrue("no Porcupine models declared", declared.isNotEmpty())

        declared.forEach { model ->
            assertNotNull(
                "models.json offers '${'$'}{model.label}', which Porcupine does not recognise",
                com.voxapps.commander.service.PorcupineWakeWordEngine.builtInKeyword(model.label)
            )
        }
    }

    /**
     * The bundled copy and the one served from the repository are compared by version to decide
     * which the app runs on, so a twin left behind is not a cosmetic difference — it decides whose
     * schema wins.
     */
    @Test
    fun `each asset and its repo-root twin are identical`() {
        listOf("models.json", "virtual_models.json").forEach { name ->
            val asset = repoFile("src/main/assets/$name")
            val twin = listOf(File(name), File("../$name")).firstOrNull { it.exists() }
                ?: error("repo-root $name not found")
            assertEquals("$name differs from its repo-root twin", asset.readText(), twin.readText())
        }
    }
}
