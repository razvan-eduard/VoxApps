package com.voxapps.expenses.domain.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CategoryMergeMappingParserTest {

    @Test
    fun `parses flat shape (duplicate maps to canonical)`() {
        val json = """{"mancare":"Mancare","transportt":"Transport"}"""
        val result = CategoryMergeMappingParser.parse(json)
        assertEquals(mapOf("mancare" to "Mancare", "transportt" to "Transport"), result)
    }

    @Test
    fun `parses grouped shape (canonical maps to array of duplicates)`() {
        val json = """{"Mancare":["mancare"],"Transport":["transportt"]}"""
        val result = CategoryMergeMappingParser.parse(json)
        assertEquals(mapOf("mancare" to "Mancare", "transportt" to "Transport"), result)
    }

    @Test
    fun `parses grouped shape with multiple duplicates per canonical`() {
        val json = """{"Mancare":["mancare","Mâncare","Groceries"]}"""
        val result = CategoryMergeMappingParser.parse(json)
        assertEquals(
            mapOf(
                "mancare" to "Mancare",
                "Mâncare" to "Mancare",
                "Groceries" to "Mancare"
            ),
            result
        )
    }

    @Test
    fun `handles mixed flat and grouped entries in the same object`() {
        val json = """{"Mancare":["mancare"],"transportt":"Transport"}"""
        val result = CategoryMergeMappingParser.parse(json)
        assertEquals(mapOf("mancare" to "Mancare", "transportt" to "Transport"), result)
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
