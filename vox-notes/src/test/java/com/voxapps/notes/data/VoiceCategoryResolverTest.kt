package com.voxapps.notes.data

import com.voxapps.notes.testutil.NotesTestDataFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceCategoryResolverTest {

    private val shopping = NotesTestDataFactory.category(id = 1, name = "Shopping")
    private val work = NotesTestDataFactory.category(id = 2, name = "Work")
    private val cats = listOf(shopping, work)

    @Test
    fun `spoken name matches case-insensitively`() {
        val r = VoiceCategoryResolver.resolve("shopping", cats, defaultCategoryId = null)
        assertEquals(1L, r.categoryId)
        assertEquals("Shopping", r.categoryName)
    }

    @Test
    fun `spoken name trims whitespace`() {
        val r = VoiceCategoryResolver.resolve("  Work  ", cats, defaultCategoryId = null)
        assertEquals(2L, r.categoryId)
    }

    @Test
    fun `unknown spoken name falls back to default`() {
        val r = VoiceCategoryResolver.resolve("Groceries", cats, defaultCategoryId = 2)
        assertEquals(2L, r.categoryId)
        assertEquals("Work", r.categoryName)
    }

    @Test
    fun `no spoken name uses default`() {
        val r = VoiceCategoryResolver.resolve(null, cats, defaultCategoryId = 1)
        assertEquals(1L, r.categoryId)
    }

    @Test
    fun `no spoken name and no default is uncategorized`() {
        val r = VoiceCategoryResolver.resolve(null, cats, defaultCategoryId = null)
        assertNull(r.categoryId)
        assertNull(r.categoryName)
    }

    @Test
    fun `default id that no longer exists is uncategorized`() {
        val r = VoiceCategoryResolver.resolve(null, cats, defaultCategoryId = 999)
        assertNull(r.categoryId)
    }
}
