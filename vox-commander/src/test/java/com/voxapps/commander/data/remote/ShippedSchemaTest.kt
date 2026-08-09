package com.voxapps.commander.data.remote

import com.google.gson.Gson
import com.voxapps.commander.domain.engine.AndroidTtsEngine
import com.voxapps.services.AuthDeclaration
import com.voxapps.services.ProbeSpec
import com.voxapps.commander.utils.Strings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URI

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

    private fun assetModels() = parse(repoFile("src/main/assets/schemas/models.json"))
    private fun assetVirtual() = parse(repoFile("src/main/assets/schemas/virtual_models.json"))

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
    fun `an archive engine's picker is filtered to archives`() {
        // Only the registered types are filtered on. A model format has no agreed type, so naming
        // one would hide a file the provider typed differently — the picker offers everything for
        // those and the name is checked after picking.
        assetModels().engines
            .filter { (_, config) -> config.extension.lowercase() in listOf(".zip", ".tar.bz2") }
            .forEach { (key, config) ->
                assertFalse(
                    "engine '$key' opens an unfiltered picker for an archive it could filter",
                    RemoteModelRegistry.mimeTypesForExtension(config.extension).contains("*/*")
                )
            }
    }

    @Test
    fun `an engine that accepts a custom model can actually receive one`() {
        // The screens gate the import on this capability alone. An engine claiming it while having
        // nowhere to put the file would offer a picker whose result goes nowhere — which is how the
        // capability came to be declared by two engines and read by none.
        (assetModels().engines + assetVirtual().engines)
            .filter { (_, config) -> "custom_model_import" in config.capabilities }
            .forEach { (key, config) ->
                assertEquals(
                    "engine '$key' accepts custom models but is not a local-file engine",
                    EngineRuntime.LOCAL_FILE,
                    EngineRuntime.fromKey(config.runtime)
                )
                assertNotNull("engine '$key' accepts custom models but declares no entry point", config.entry)
            }
    }

    @Test
    fun `every local file voice engine says whether it accepts a custom model`() {
        // Not a claim that they all should — Porcupine's keywords are licence-locked and it says no.
        // The point is that the answer is written down rather than inferred from the file extension,
        // which is what the settings screen used to do and what made stt_whisper's import work while
        // the schema said it did not exist.
        assetModels().engines
            .filter { (_, config) -> EngineRuntime.fromKey(config.runtime) == EngineRuntime.LOCAL_FILE }
            .forEach { (key, config) ->
                assertTrue(
                    "engine '$key' has an extension but no import capability either way — decide in the schema",
                    config.extension.isBlank() || config.capabilities.isNotEmpty()
                )
            }
    }

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

    private fun assetSearch(): com.voxapps.commander.domain.search.SearchDefinitionsSchema =
        gson.fromJson(
            repoFile("src/main/assets/schemas/search_definitions.json").readText(),
            com.voxapps.commander.domain.search.SearchDefinitionsSchema::class.java
        )

    private fun declaredProviders() = assetSearch().categories.flatMap { it.providers }

    /**
     * Every search provider is testable, and its test reaches its own service.
     *
     * The probe is resolved against the endpoint precisely so a schema cannot send a credential
     * somewhere else; asserting the resolved host still matches keeps that true for the shipped
     * declarations rather than only for the resolver.
     */
    @Test
    fun `every declared search provider resolves to a probe on its own host`() {
        val providers = declaredProviders()
        assertTrue("no search providers declared", providers.isNotEmpty())

        providers.forEach { def ->
            val endpoint = def.endpoint.replace("{lang}", "en")
            val spec = ProbeSpec.from(def.name, endpoint, def.probeUrl)
                ?: error("provider '${def.name}' declares nothing to probe")

            assertEquals(
                "provider '${def.name}' probes a different host than it calls",
                URI(endpoint).host,
                URI(spec.url).host
            )
        }
    }

    /**
     * A provider that borrows a credential names the engine it borrows from, and that engine exists.
     *
     * The boolean this replaced could only ever mean OpenAI, because the engine it borrowed from was
     * written in the registry rather than declared — so a second provider sharing a different
     * engine's key had nowhere to say so.
     */
    @Test
    fun `a borrowed credential names an engine that is declared`() {
        val declared = assetVirtual().engines.keys + assetModels().engines.keys

        declaredProviders().mapNotNull { it.sharedKeyEngine }.forEach { engineKey ->
            assertTrue("provider borrows from undeclared engine '$engineKey'", engineKey in declared)
        }
    }

    /**
     * A provider that needs a key must say how the key attaches, or the probe sends none and the
     * screen reports the service unreachable when it is the credential that is missing.
     */
    @Test
    fun `a search provider that requires a key declares how it travels`() {
        declaredProviders().filter { it.requiresApiKey }.forEach { def ->
            val style = def.auth?.probeStyle()
            assertTrue(
                "provider '${def.name}' requires an API key but declares no auth style",
                style is ProbeSpec.AuthStyle.Bearer || style is ProbeSpec.AuthStyle.Query
            )
        }
    }

    private fun assetIntegrations(): com.voxapps.commander.domain.intent.registry.ApiIntegrationsSchema =
        gson.fromJson(
            repoFile("src/main/assets/schemas/api_integrations.json").readText(),
            com.voxapps.commander.domain.intent.registry.ApiIntegrationsSchema::class.java
        )

    /**
     * One vocabulary, asserted on the files rather than trusted.
     *
     * Each schema arrived with its own words for the same three facts — where the service lives,
     * what proves it answers, how the credential attaches — and the code that read them was written
     * once per vocabulary. The readers still accept the old spellings, because a copy served from a
     * user's repository may predate the rename; what must not drift is what the app itself ships.
     */
    @Test
    fun `every shipped schema uses the shared vocabulary`() {
        val endpoints = mutableListOf<Pair<String, String>>()

        assetVirtual().engines.forEach { (key, config) ->
            config.endpoint?.let { endpoints += key to it }
        }
        assetIntegrations().integrations.forEach { integration ->
            assertTrue(
                "integration '${integration.id}' still uses base_url",
                integration.legacyBaseUrl == null
            )
            endpoints += integration.id to integration.serviceUrl
        }
        declaredProviders().forEach { endpoints += it.name to it.endpoint }
        assetMedia().backends.forEach { backend ->
            backend.endpoints.forEach { endpoints += backend.id to it }
        }

        assertTrue("no endpoints declared anywhere", endpoints.isNotEmpty())
        endpoints.forEach { (owner, endpoint) ->
            assertTrue(
                "'$owner' declares a non-https endpoint: $endpoint",
                endpoint.replace("{lang}", "en").startsWith("https://")
            )
        }
    }

    /**
     * An auth style is a choice among the ones the app implements, never a description of a new one
     * — a schema supplies data, not a way of authenticating that no code exists for.
     */
    @Test
    fun `every declared auth style is one the prober implements`() {
        val declarations = assetVirtual().engines.values.mapNotNull { it.auth } +
            assetIntegrations().integrations.mapNotNull { it.auth } +
            declaredProviders().mapNotNull { it.auth }

        assertTrue("no auth declared anywhere", declarations.isNotEmpty())
        declarations.forEach { auth ->
            assertTrue(
                "unknown auth style '${auth.effectiveStyle}'",
                auth.effectiveStyle in setOf(
                    AuthDeclaration.STYLE_NONE,
                    AuthDeclaration.STYLE_BEARER,
                    AuthDeclaration.STYLE_QUERY,
                    AuthDeclaration.STYLE_OAUTH2
                )
            )
        }
    }

    /**
     * An OAuth service says which flow it uses, because the two are not interchangeable: the app
     * defaults to PKCE, so a service needing the authorization-code flow and not saying so fails at
     * the token exchange rather than at load.
     */
    @Test
    fun `every OAuth integration declares its flow`() {
        assetIntegrations().integrations.mapNotNull { it.auth }.filter { it.isOAuth }.forEach { auth ->
            assertTrue(
                "an OAuth declaration names no flow",
                auth.flow in setOf(AuthDeclaration.FLOW_PKCE, AuthDeclaration.FLOW_AUTHORIZATION_CODE)
            )
        }
    }

    private fun assetMedia(): com.voxapps.commander.domain.media.MediaServiceRegistry.MediaSchema =
        gson.fromJson(
            repoFile("src/main/assets/schemas/media_services.json").readText(),
            com.voxapps.commander.domain.media.MediaServiceRegistry.MediaSchema::class.java
        )

    /**
     * A media backend either has instances to call or says it is compiled in — never neither, which
     * would be a backend the settings screen offers and nothing can reach.
     */
    @Test
    fun `every media backend is either reachable or built in`() {
        val backends = assetMedia().backends
        assertTrue("no media backends declared", backends.isNotEmpty())

        backends.forEach { backend ->
            if (backend.isBuiltIn) {
                assertTrue(
                    "built-in backend '${backend.id}' declares endpoints",
                    backend.endpoints.isEmpty()
                )
            } else {
                assertTrue(
                    "backend '${backend.id}' declares no endpoint",
                    backend.endpoints.isNotEmpty()
                )
                backend.endpoints.forEach { endpoint ->
                    assertTrue(
                        "backend '${backend.id}' declares a non-https endpoint: $endpoint",
                        endpoint.startsWith("https://")
                    )
                    assertNotNull(
                        "endpoint $endpoint yields no probe",
                        ProbeSpec.from(backend.id, endpoint, backend.probeUrl)
                    )
                }
            }
        }
    }

    /** Exactly one default, or the screen's choice of what to select first is arbitrary. */
    @Test
    fun `exactly one media backend is the default`() {
        assertEquals(1, assetMedia().backends.count { it.isDefault })
    }

    /**
     * `remote-schemas/` is what the repository serves *and* what the build copies into assets, so a
     * stale asset copy means the app ships one thing and the repository offers another — and since a
     * refresh compares them by hash, the difference would show up as a permanent "update available".
     */
    @Test
    fun `each shipped asset matches the copy in remote-schemas`() {
        listOf(
            "models.json",
            "virtual_models.json",
            "search_definitions.json",
            "api_integrations.json",
            "media_services.json"
        ).forEach { name ->
            val asset = repoFile("src/main/assets/schemas/$name")
            val source = listOf(
                File("remote-schemas/commander/$name"),
                File("../remote-schemas/commander/$name")
            ).firstOrNull { it.exists() } ?: error("remote-schemas/commander/$name not found")
            assertEquals("$name differs from the copy in remote-schemas/", asset.readText(), source.readText())
        }
    }
}
