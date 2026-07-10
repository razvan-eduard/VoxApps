package com.voxapps.expenses.domain.llm

import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryMergePromptBuilderTest {

    @Test
    fun `includes all category names and the language code`() {
        val prompt = CategoryMergePromptBuilder.build(listOf("Mancare", "Transport", "Facturi"), "ro")

        assertTrue(prompt.contains("Mancare"))
        assertTrue(prompt.contains("Transport"))
        assertTrue(prompt.contains("Facturi"))
        assertTrue(prompt.contains("\"ro\""))
    }

    @Test
    fun `asks for JSON-only output`() {
        val prompt = CategoryMergePromptBuilder.build(listOf("A", "B"), "en")
        assertTrue(prompt.contains("JSON"))
        assertTrue(prompt.contains("no markdown"))
    }
}
