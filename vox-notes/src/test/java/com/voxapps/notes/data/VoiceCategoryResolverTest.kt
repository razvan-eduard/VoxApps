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

    @Test
    fun `fuzzy match ignores diacritics`() {
        val cumparaturi = NotesTestDataFactory.category(id = 3, name = "Cumpărături")
        val catsWithDiacritics = cats + cumparaturi
        val r = VoiceCategoryResolver.resolve("cumparaturi", catsWithDiacritics, defaultCategoryId = null)
        assertEquals(3L, r.categoryId)
        assertEquals("Cumpărături", r.categoryName)
    }

    @Test
    fun `fuzzy match handles minor typos`() {
        val r = VoiceCategoryResolver.resolve("Shoping", cats, defaultCategoryId = null)
        assertEquals(1L, r.categoryId)
        assertEquals("Shopping", r.categoryName)
    }

    @Test
    fun `fuzzy match rejects dissimilar names and falls back to default`() {
        val cumparaturi = NotesTestDataFactory.category(id = 3, name = "Cumpărături")
        val catsWithDiacritics = cats + cumparaturi
        val r = VoiceCategoryResolver.resolve("Groceries", catsWithDiacritics, defaultCategoryId = 3)
        assertEquals(3L, r.categoryId)
        assertEquals("Cumpărături", r.categoryName)
    }
}
