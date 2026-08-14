package com.voxapps.textmatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `namesMatch is case-insensitive on an exact match`() {
        assertTrue(FuzzyNameMatcher.namesMatch("Example Store", "example store"))
    }

    @Test
    fun `namesMatch matches when one name fully contains the other`() {
        assertTrue(FuzzyNameMatcher.namesMatch("Example Store", "Payment to Example Store"))
        assertTrue(FuzzyNameMatcher.namesMatch("Payment to Example Store", "Example Store"))
    }

    @Test
    fun `namesMatch does not use containment for very short names`() {
        assertFalse(FuzzyNameMatcher.namesMatch("A", "A long unrelated description"))
    }

    @Test
    fun `namesMatch accepts a minor typo within the fuzzy threshold`() {
        assertTrue(FuzzyNameMatcher.namesMatch("Example Store", "Exampl Store"))
    }

    @Test
    fun `namesMatch rejects genuinely unrelated names`() {
        assertFalse(FuzzyNameMatcher.namesMatch("Example Store", "Completely Different Vendor"))
    }

    @Test
    fun `namesMatch rejects blank input`() {
        assertFalse(FuzzyNameMatcher.namesMatch("", "Example Store"))
    }

    @Test
    fun `leveled level 0 is exact normalized equality only`() {
        assertTrue(FuzzyNameMatcher.namesMatchLeveled(" LIDL ", "lidl", 0))
        assertFalse(FuzzyNameMatcher.namesMatchLeveled("Lidll", "lidl", 0))
    }

    @Test
    fun `leveled levels get progressively easier`() {
        // One edit in a five-letter word: within 15% is impossible, but the floor of 1 admits it.
        assertTrue(FuzzyNameMatcher.namesMatchLeveled("Lidll", "lidl", 1))
        // Containment only unlocks at level 2.
        assertFalse(FuzzyNameMatcher.namesMatchLeveled("Lidl Supermarket", "lidl", 1))
        assertTrue(FuzzyNameMatcher.namesMatchLeveled("Lidl Supermarket", "lidl", 2))
        // Four edits in a nine-letter pair: beyond 30%, within 45%.
        assertFalse(FuzzyNameMatcher.namesMatchLeveled("kaufhalle", "kaufland", 2))
        assertTrue(FuzzyNameMatcher.namesMatchLeveled("kaufhalle", "kaufland", 3))
    }
}
