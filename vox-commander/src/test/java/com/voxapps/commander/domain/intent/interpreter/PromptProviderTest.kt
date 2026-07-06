package com.voxapps.commander.domain.intent.interpreter

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
}
