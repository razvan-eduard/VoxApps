package com.voxapps.notes.domain.llm

import org.junit.Assert.assertTrue
import org.junit.Test

class NoteDeduplicationPromptBuilderTest {

    @Test
    fun `includes note ids, titles, and text`() {
        val prompt = NoteDeduplicationPromptBuilder.build(
            listOf(
                NoteSummary(id = 1, title = "Groceries", text = "Milk, bread, eggs"),
                NoteSummary(id = 2, title = null, text = "Milk, bread, eggs, butter")
            )
        )

        assertTrue(prompt.contains("id=1"))
        assertTrue(prompt.contains("id=2"))
        assertTrue(prompt.contains("Groceries"))
        assertTrue(prompt.contains("Milk, bread, eggs"))
    }

    @Test
    fun `asks for JSON-only output`() {
        val prompt = NoteDeduplicationPromptBuilder.build(
            listOf(NoteSummary(id = 1, title = null, text = "a"), NoteSummary(id = 2, title = null, text = "b"))
        )
        assertTrue(prompt.contains("JSON"))
        assertTrue(prompt.contains("no markdown"))
        assertTrue(prompt.contains("\"keep\""))
        assertTrue(prompt.contains("\"duplicates\""))
    }

    @Test
    fun `truncates long note text`() {
        val longText = "a".repeat(1000)
        val prompt = NoteDeduplicationPromptBuilder.build(listOf(NoteSummary(id = 1, title = null, text = longText)))
        assertTrue(prompt.contains(longText).not())
    }
}
