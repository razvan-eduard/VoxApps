package com.voxapps.notes.domain.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CategoryMergeMappingParserTest {

    @Test
    fun `parses flat shape (duplicate maps to canonical)`() {
        val json = """{"kumparaturi":"Cumparaturi","testt":"test"}"""
        val result = CategoryMergeMappingParser.parse(json)
        assertEquals(mapOf("kumparaturi" to "Cumparaturi", "testt" to "test"), result)
    }

    @Test
    fun `parses grouped shape (canonical maps to array of duplicates)`() {
        val json = """{"Cumparaturi":["kumparaturi"],"test":["testt"]}"""
        val result = CategoryMergeMappingParser.parse(json)
        assertEquals(mapOf("kumparaturi" to "Cumparaturi", "testt" to "test"), result)
    }

    @Test
    fun `parses grouped shape with multiple duplicates per canonical`() {
        val json = """{"Cumparaturi":["kumparaturi","Cumpărături","Groceries"]}"""
        val result = CategoryMergeMappingParser.parse(json)
        assertEquals(
            mapOf(
                "kumparaturi" to "Cumparaturi",
                "Cumpărături" to "Cumparaturi",
                "Groceries" to "Cumparaturi"
            ),
            result
        )
    }

    @Test
    fun `handles mixed flat and grouped entries in the same object`() {
        val json = """{"Cumparaturi":["kumparaturi"],"testt":"test"}"""
        val result = CategoryMergeMappingParser.parse(json)
        assertEquals(mapOf("kumparaturi" to "Cumparaturi", "testt" to "test"), result)
    }

    @Test
    fun `empty object returns empty map`() {
        assertEquals(emptyMap<String, String>(), CategoryMergeMappingParser.parse("{}"))
    }

    @Test
    fun `malformed json returns null`() {
        assertNull(CategoryMergeMappingParser.parse("{ not json"))
    }
}
