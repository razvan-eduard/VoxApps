package com.voxapps.notes.domain.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoteScanCleanupResultParserTest {

    @Test
    fun `parses title, category, and text`() {
        val json = """{"title":"Bon Dr.Max","category":"Cumparaturi","text":"Spray anti tantari 33.99 LEI"}"""
        val result = NoteScanCleanupResultParser.parse(json)
        assertEquals(NoteScanCleanupResultParser.Cleaned("Bon Dr.Max", "Cumparaturi", "Spray anti tantari 33.99 LEI"), result)
    }

    @Test
    fun `missing category is null`() {
        val json = """{"title":"Note","text":"Some text"}"""
        val result = NoteScanCleanupResultParser.parse(json)
        assertEquals("Note", result?.title)
        assertNull(result?.category)
        assertEquals("Some text", result?.text)
    }

    @Test
    fun `blank category is null`() {
        val json = """{"title":"Note","category":"","text":"Some text"}"""
        val result = NoteScanCleanupResultParser.parse(json)
        assertNull(result?.category)
    }

    @Test
    fun `missing text returns null`() {
        assertNull(NoteScanCleanupResultParser.parse("""{"title":"x","category":"y"}"""))
    }

    @Test
    fun `malformed json returns null`() {
        assertNull(NoteScanCleanupResultParser.parse("{ not json"))
    }
}
