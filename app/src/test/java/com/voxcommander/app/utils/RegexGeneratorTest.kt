package com.voxcommander.app.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [RegexGenerator] — verifies generated patterns match the intended phrases,
 * are diacritic-insensitive, order-preserving, and escape regex metacharacters.
 */
class RegexGeneratorTest {

    @Test
    fun `fromWords matches words in order with anything between`() {
        val p = Regex(RegexGenerator.fromWords(listOf("turn", "light")))
        assertTrue(p.containsMatchIn("please turn on the light"))
        assertTrue(p.containsMatchIn("turn light"))
        // order matters — "light" before "turn" must not match
        assertFalse(p.containsMatchIn("the light and then turn"))
    }

    @Test
    fun `fromWords is diacritic-insensitive`() {
        // ASCII query "maine" must also match the diacritic form "mâine" (a<->â, i<->î),
        // via the char classes (NOT via a Unicode \b flag). Word ends in ASCII 'e'.
        val p = Regex(RegexGenerator.fromWords(listOf("maine")), RegexOption.IGNORE_CASE)
        assertTrue(p.containsMatchIn("ne vedem maine"))
        assertTrue(p.containsMatchIn("ne vedem mâine"))
    }

    @Test
    fun `generated patterns avoid the Android-incompatible Unicode flag`() {
        // Regression guard: Android's regex engine throws on the (?U) inline flag
        // (desktop JVM accepts it, so it can't be caught by matching here). Pin that the
        // generator never emits it.
        assertFalse(RegexGenerator.fromWords(listOf("play", "music")).contains("(?U"))
        assertFalse(RegexGenerator.fromWordGroups(listOf(listOf("open"), listOf("start"))).contains("(?U"))
    }

    @Test
    fun `fromWords escapes regex metacharacters`() {
        // "a.b" must match literally, NOT treat '.' as a wildcard.
        val p = Regex(RegexGenerator.fromWords(listOf("a.b")))
        assertTrue(p.containsMatchIn("value a.b here"))
        assertFalse(p.containsMatchIn("value axb here"))
    }

    @Test
    fun `fromWords empty list yields empty string`() {
        assertEquals("", RegexGenerator.fromWords(emptyList()))
    }

    @Test
    fun `fromWordGroups matches any group (OR)`() {
        val p = Regex(RegexGenerator.fromWordGroups(listOf(listOf("open"), listOf("start"))))
        assertTrue(p.containsMatchIn("please open it"))
        assertTrue(p.containsMatchIn("please start it"))
        assertFalse(p.containsMatchIn("please close it"))
    }

    @Test
    fun `fromWordGroups ignores empty groups`() {
        assertEquals("", RegexGenerator.fromWordGroups(listOf(emptyList())))
    }

    @Test
    fun `splitIntoTokens strips punctuation and blanks`() {
        assertEquals(listOf("Hello", "world", "foo"), RegexGenerator.splitIntoTokens("Hello, world!  foo"))
        assertEquals(emptyList<String>(), RegexGenerator.splitIntoTokens("   "))
    }
}
