package com.voxapps.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationParityTest {

    @Test
    fun `identical sets pass`() {
        val keys = setOf("a", "b")
        assertTrue(TranslationParity.problems(mapOf("en" to keys, "ro" to keys, "de" to keys)).isEmpty())
    }

    @Test
    fun `romanian _many with a base is sanctioned`() {
        val en = setOf("items", "items_one")
        val ro = setOf("items", "items_one", "items_many")
        assertTrue(TranslationParity.problems(mapOf("en" to en, "ro" to ro)).isEmpty())
    }

    @Test
    fun `_many without its base is a violation`() {
        val en = setOf("items")
        val ro = setOf("items", "ghost_many")
        assertEquals(1, TranslationParity.problems(mapOf("en" to en, "ro" to ro)).size)
    }

    @Test
    fun `_many outside romanian is a violation`() {
        val en = setOf("items")
        val de = setOf("items", "items_many")
        assertEquals(1, TranslationParity.problems(mapOf("en" to en, "de" to de)).size)
    }

    @Test
    fun `a missing key is named`() {
        val problems = TranslationParity.problems(mapOf("en" to setOf("a", "b"), "fr" to setOf("a")))
        assertEquals(listOf("fr is missing 'b'"), problems)
    }
}
