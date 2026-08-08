package com.voxapps.commander.domain.intent.interpreter

import android.util.Log
import com.google.gson.Gson
import com.voxapps.commander.data.remote.EngineRuntime
import com.voxapps.commander.data.remote.RemoteModelRegistry
import com.voxapps.commander.data.remote.RemoteModelSchema
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import com.voxapps.ipc.VoxAppInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tests for [PromptProvider.stripToRules] — the NLU template is cut to rules-only
 * (few-shot examples + trailing input placeholder removed, no dangling header).
 */
class PromptProviderTest {

    /**
     * The template an engine is read is resolved, not fixed: what it declares for itself, else one
     * written for its runtime, else the standard one. A hosted model and a half-gigabyte on-device
     * one are not the same audience, and until now they were handed identical text.
     */
    private fun stubRegistry(
        declaredId: String? = null,
        runtime: EngineRuntime? = null,
        available: Map<String, String> = emptyMap()
    ) {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0

        mockkObject(RemoteModelRegistry)
        every { RemoteModelRegistry.declaredPromptId(any()) } returns declaredId
        every { RemoteModelRegistry.runtimeOf(any()) } returns runtime
        every { RemoteModelRegistry.getPrompt(any()) } answers { available[firstArg<String>()] }
    }

    @org.junit.After
    fun tearDown() {
        unmockkObject(RemoteModelRegistry)
    }

    @Test
    fun `an engine's own declared prompt wins over every other candidate`() {
        stubRegistry(
            declaredId = "nlu_tiny",
            runtime = EngineRuntime.LOCAL_FILE,
            available = mapOf(
                "nlu_tiny" to "DECLARED",
                "standard_nlu_local_file" to "BY_RUNTIME",
                "standard_nlu" to "STANDARD"
            )
        )

        val prompt = PromptProvider.getNluSystemPrompt(engineKey = "nlu_llm")

        assertTrue(prompt.startsWith("DECLARED"))
    }

    @Test
    fun `an engine declaring nothing gets the prompt written for its runtime`() {
        stubRegistry(
            runtime = EngineRuntime.LOCAL_FILE,
            available = mapOf(
                "standard_nlu_local_file" to "BY_RUNTIME",
                "standard_nlu" to "STANDARD"
            )
        )

        val prompt = PromptProvider.getNluSystemPrompt(engineKey = "nlu_llm")

        assertTrue(prompt.startsWith("BY_RUNTIME"))
    }

    @Test
    fun `a runtime with no prompt of its own falls through to the standard one`() {
        stubRegistry(
            runtime = EngineRuntime.CLOUD,
            available = mapOf("standard_nlu" to "STANDARD")
        )

        val prompt = PromptProvider.getNluSystemPrompt(engineKey = "OPENAI")

        assertTrue(prompt.startsWith("STANDARD"))
    }

    /** Every caller today: nothing declares a prompt and no per-runtime prompt is written yet. */
    @Test
    fun `an unknown engine gets the standard prompt`() {
        stubRegistry(available = mapOf("standard_nlu" to "STANDARD"))

        val prompt = PromptProvider.getNluSystemPrompt(engineKey = "who_knows")

        assertTrue(prompt.startsWith("STANDARD"))
    }

    private val template = """
        Sentence Anatomy Rules:
        1. action_verb: ...
        2. domain: choose from ${'$'}{domains}.
        ${'$'}{installedApps}
        ${'$'}{searchProviders}

        Examples:
        Input: "play scorpions"
        Output: {"action":"play"}
        Input: "${'$'}{spokenText}"
        JSON:
    """.trimIndent()

    @Test
    fun `strips examples, dangling header and input placeholder`() {
        val rules = PromptProvider.stripToRules(template)
        assertTrue("keeps the rules", rules.contains("Sentence Anatomy Rules:"))
        assertTrue("keeps placeholders for later injection", rules.contains("\${installedApps}"))
        assertFalse("no dangling Examples: header", rules.contains("Examples:"))
        assertFalse("no example input", rules.contains("play scorpions"))
        assertFalse("no trailing input placeholder", rules.contains("\${spokenText}"))
        assertFalse("no trailing JSON: marker", rules.trimEnd().endsWith("JSON:"))
    }

    @Test
    fun `fallback drops only the input line when there is no Examples section`() {
        val noExamples = "Rules here.\nInput: \"\${spokenText}\"\nJSON:"
        val rules = PromptProvider.stripToRules(noExamples)
        assertEquals("Rules here.", rules)
    }

    @Test
    fun `a rule appended after Examples never reaches the model`() {
        // Guards the exact failure mode found in production: SATELLITE OVERRIDE was appended after
        // the Examples: section in models.json and was silently discarded by stripToRules, so the
        // LLM never saw it (notes never got their category/content extracted correctly).
        val misplaced = "$template\n\nSATELLITE OVERRIDE: this rule was appended too late."
        val rules = PromptProvider.stripToRules(misplaced)
        assertFalse("a rule placed after Examples: is lost", rules.contains("SATELLITE OVERRIDE"))
    }

    @Test
    fun `a rule placed before Examples survives stripToRules`() {
        val withOverride = template.replace(
            "\${searchProviders}",
            "\${searchProviders}\n\nSATELLITE OVERRIDE: this rule must reach the model."
        )
        val rules = PromptProvider.stripToRules(withOverride)
        assertTrue("a rule placed before Examples: survives the cut", rules.contains("SATELLITE OVERRIDE"))
    }

    @Test
    fun `the real models json prompt keeps SATELLITE OVERRIDE before the Examples cut`() {
        // Reads the actual repo-root models.json (single source of truth, copied into assets at
        // build time) to make sure this specific rule never regresses back to living after
        // Examples: again.
        val modelsJson = File("../models.json")
        val schema = Gson().fromJson(modelsJson.readText(), RemoteModelSchema::class.java)
        val template = requireNotNull(schema.prompts?.get("standard_nlu")) { "standard_nlu prompt missing from models.json" }

        assertTrue(
            "SATELLITE OVERRIDE must be present in models.json's standard_nlu prompt",
            template.contains("SATELLITE OVERRIDE")
        )
        val rules = PromptProvider.stripToRules(template)
        assertTrue(
            "SATELLITE OVERRIDE must survive stripToRules (i.e. be placed before Examples:)",
            rules.contains("SATELLITE OVERRIDE")
        )
        assertFalse(
            "the shared SATELLITE OVERRIDE rule must stay domain-agnostic — the notes-specific " +
                "category extraction now lives in vox-notes's own nluHint manifest declaration, " +
                "surfaced via buildSatelliteHints instead",
            rules.contains("target list/category")
        )
    }

    // --- Satellite-declared NLU hints (injected dynamically, no models.json edit per satellite) ---

    private fun sat(domain: String?, hint: String?) =
        VoxAppInfo(packageName = "com.x.$domain", label = domain ?: "x", domain = domain, actions = emptyList(), nluHint = hint)

    @Test
    fun `no satellite hint appends nothing`() {
        assertEquals("", PromptProvider.buildSatelliteHints(emptyList()))
        assertEquals("", PromptProvider.buildSatelliteHints(listOf(sat("notes", null), sat("notes", "  "))))
    }

    @Test
    fun `hinted satellites are appended as domain-specific lines`() {
        val out = PromptProvider.buildSatelliteHints(
            listOf(sat("expenses", "amount -> extras.amount"), sat("tasks", "due -> extras.due"))
        )
        assertTrue(out.contains("Domain-specific extraction:"))
        assertTrue(out.contains("- expenses: amount -> extras.amount"))
        assertTrue(out.contains("- tasks: due -> extras.due"))
    }

    @Test
    fun `hint without a domain is skipped`() {
        assertEquals("", PromptProvider.buildSatelliteHints(listOf(sat(null, "some hint"))))
    }

    // --- Domain prefiltering (relevantDomains) ---

    @Test
    fun `an utterance naming a domain's keyword includes only that domain`() {
        val domainKeywords = mapOf(
            "audio" to listOf("spotify", "play", "music"),
            "maps" to listOf("waze", "navigate"),
            "messaging" to listOf("whatsapp")
        )
        val result = PromptProvider.relevantDomains("play something on spotify", domainKeywords)
        assertEquals(setOf("audio"), result)
    }

    @Test
    fun `an utterance matching two domains includes both`() {
        val domainKeywords = mapOf(
            "audio" to listOf("spotify"),
            "maps" to listOf("waze"),
            "messaging" to listOf("whatsapp")
        )
        val result = PromptProvider.relevantDomains("navigate with waze then play spotify", domainKeywords)
        assertEquals(setOf("audio", "maps"), result)
    }

    @Test
    fun `matching is case-insensitive`() {
        val domainKeywords = mapOf("audio" to listOf("Spotify"))
        assertEquals(setOf("audio"), PromptProvider.relevantDomains("SPOTIFY please", domainKeywords))
    }

    @Test
    fun `no keyword match falls back to every domain, never an empty set`() {
        // Safety net: a false negative here would silently break app-targeting for that command,
        // which is worse than the token bloat this prefilter exists to avoid — e.g. "play some
        // music" names no specific app, but audio is still clearly the right domain.
        val domainKeywords = mapOf(
            "audio" to listOf("spotify"),
            "maps" to listOf("waze")
        )
        val result = PromptProvider.relevantDomains("what's the weather today", domainKeywords)
        assertEquals(domainKeywords.keys, result)
    }

    @Test
    fun `blank keywords in a domain's list are never treated as a universal match`() {
        val domainKeywords = mapOf(
            "audio" to listOf("", "spotify"),
            "maps" to listOf("")
        )
        // "maps" only has a blank keyword — must never match arbitrary text via an empty
        // substring check (every string "contains" "").
        val result = PromptProvider.relevantDomains("call mom on whatsapp", domainKeywords)
        assertEquals(domainKeywords.keys, result) // no real match anywhere -> falls back to all
    }

    @Test
    fun `an empty domainKeywords map returns an empty set, not a crash`() {
        assertEquals(emptySet<String>(), PromptProvider.relevantDomains("anything", emptyMap()))
    }
}
