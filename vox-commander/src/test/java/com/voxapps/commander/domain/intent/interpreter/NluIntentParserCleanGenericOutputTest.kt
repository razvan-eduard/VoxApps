package com.voxapps.commander.domain.intent.interpreter

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [NluIntentParser.cleanGenericOutput] — the generic (domain-agnostic) cleanup applied to
 * satellite LLM-hook raw prompt responses before Commander replies over IPC.
 */
class NluIntentParserCleanGenericOutputTest {

    @Test
    fun `extracts JSON from a markdown-fenced response`() {
        val raw = "```json\n{\"Groceries\":\"Cumpărături\"}\n```"
        assertEquals("""{"Groceries":"Cumpărături"}""", NluIntentParser.cleanGenericOutput(raw))
    }

    @Test
    fun `extracts JSON block from conversational wrapper text`() {
        val raw = "Sure! Here's the mapping you asked for:\n{\"Groceries\":\"Cumpărături\"}\nLet me know if you need more."
        assertEquals("""{"Groceries":"Cumpărături"}""", NluIntentParser.cleanGenericOutput(raw))
    }

    @Test
    fun `bare JSON passes through unchanged (trimmed)`() {
        val raw = "  {\"a\":\"b\"}  "
        assertEquals("""{"a":"b"}""", NluIntentParser.cleanGenericOutput(raw))
    }

    @Test
    fun `non-JSON text is returned trimmed, not discarded`() {
        val raw = "  This is just a plain text answer.  "
        assertEquals("This is just a plain text answer.", NluIntentParser.cleanGenericOutput(raw))
    }
}
