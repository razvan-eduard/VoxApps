package com.voxapps.commander.domain.intent.interpreter

import com.voxapps.commander.domain.integration.VoxAppInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [PromptProvider.stripToRules] — the NLU template is cut to rules-only
 * (few-shot examples + trailing input placeholder removed, no dangling header).
 */
class PromptProviderTest {

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
}
