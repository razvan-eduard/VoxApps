package com.voxapps.textmatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FuzzyNameMatcherTest {

    private val shopping = FuzzyNameMatcher.Candidate(id = 1, name = "Shopping")
    private val work = FuzzyNameMatcher.Candidate(id = 2, name = "Work")
    private val cats = listOf(shopping, work)

    @Test
    fun `spoken name matches case-insensitively`() {
        val r = FuzzyNameMatcher.resolve("shopping", cats, defaultId = null)
        assertEquals(1L, r.id)
        assertEquals("Shopping", r.name)
    }

    @Test
    fun `spoken name trims whitespace`() {
        val r = FuzzyNameMatcher.resolve("  Work  ", cats, defaultId = null)
        assertEquals(2L, r.id)
    }

    @Test
    fun `unknown spoken name falls back to default`() {
        val r = FuzzyNameMatcher.resolve("Groceries", cats, defaultId = 2)
        assertEquals(2L, r.id)
        assertEquals("Work", r.name)
    }

    @Test
    fun `no spoken name uses default`() {
        val r = FuzzyNameMatcher.resolve(null, cats, defaultId = 1)
        assertEquals(1L, r.id)
    }

    @Test
    fun `no spoken name and no default is unresolved`() {
        val r = FuzzyNameMatcher.resolve(null, cats, defaultId = null)
        assertNull(r.id)
        assertNull(r.name)
    }

    @Test
    fun `default id that no longer exists is unresolved`() {
        val r = FuzzyNameMatcher.resolve(null, cats, defaultId = 999)
        assertNull(r.id)
    }

    @Test
    fun `fuzzy match ignores diacritics`() {
        val cumparaturi = FuzzyNameMatcher.Candidate(id = 3, name = "Cumpărături")
        val catsWithDiacritics = cats + cumparaturi
        val r = FuzzyNameMatcher.resolve("cumparaturi", catsWithDiacritics, defaultId = null)
        assertEquals(3L, r.id)
        assertEquals("Cumpărături", r.name)
    }

    @Test
    fun `fuzzy match handles minor typos`() {
        val r = FuzzyNameMatcher.resolve("Shoping", cats, defaultId = null)
        assertEquals(1L, r.id)
        assertEquals("Shopping", r.name)
    }

    @Test
    fun `fuzzy match rejects dissimilar names and falls back to default`() {
        val cumparaturi = FuzzyNameMatcher.Candidate(id = 3, name = "Cumpărături")
        val catsWithDiacritics = cats + cumparaturi
        val r = FuzzyNameMatcher.resolve("Groceries", catsWithDiacritics, defaultId = 3)
        assertEquals(3L, r.id)
        assertEquals("Cumpărături", r.name)
    }
}
