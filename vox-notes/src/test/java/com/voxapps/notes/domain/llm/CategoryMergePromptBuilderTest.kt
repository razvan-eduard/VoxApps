package com.voxapps.notes.domain.llm

import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryMergePromptBuilderTest {

    @Test
    fun `includes all category names and the language code`() {
        val prompt = CategoryMergePromptBuilder.build(listOf("Groceries", "Cumpărături", "Bills"), "ro")

        assertTrue(prompt.contains("Groceries"))
        assertTrue(prompt.contains("Cumpărături"))
        assertTrue(prompt.contains("Bills"))
        assertTrue(prompt.contains("\"ro\""))
    }

    @Test
    fun `asks for JSON-only output`() {
        val prompt = CategoryMergePromptBuilder.build(listOf("A", "B"), "en")
        assertTrue(prompt.contains("JSON"))
        assertTrue(prompt.contains("no markdown"))
    }
}
