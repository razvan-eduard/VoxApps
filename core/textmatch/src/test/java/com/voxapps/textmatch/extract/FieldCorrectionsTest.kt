package com.voxapps.textmatch.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldCorrectionsTest {

    // --- diff: what a single-word fix teaches ---

    @Test
    fun `a one-word fix inside an unchanged field is learned`() {
        val c = FieldCorrections.diff("cartosşi prăjiți", "cartofi prăjiți")
        assertEquals("cartosşi", c?.garbageKey)
        assertEquals("cartofi", c?.fix)
    }

    @Test
    fun `punctuation garble keys by termKey`() {
        val c = FieldCorrections.diff("Denti\$t appointment", "Dentist appointment")
        // "$" splits the word into single-char-run-merged tokens under termKey.
        assertEquals(VocabularyClassifier.termKey("Denti\$t"), c?.garbageKey)
        assertEquals("Dentist", c?.fix)
    }

    // --- diff: the decline classes ---

    @Test
    fun `different word counts decline`() {
        assertNull(FieldCorrections.diff("cartofi prăjiți", "cartofi"))
        assertNull(FieldCorrections.diff("cartofi", "cartofi prăjiți mari"))
    }

    @Test
    fun `two differing positions decline`() {
        assertNull(FieldCorrections.diff("aab bbc unchanged", "aax bby unchanged"))
    }

    @Test
    fun `identical fields decline`() {
        assertNull(FieldCorrections.diff("same text", "same text"))
        assertNull(FieldCorrections.diff(null, "same"))
        assertNull(FieldCorrections.diff("same", null))
    }

    @Test
    fun `numeric edits decline`() {
        assertNull(FieldCorrections.diff("cola 500 ml", "cola 330 ml"))
        assertNull(FieldCorrections.diff("item 12,50", "item 13,00"))
    }

    @Test
    fun `styling-only changes decline`() {
        // Case and punctuation are already identity under termKey — nothing to learn.
        assertNull(FieldCorrections.diff("S.R.L. Popescu", "SRL Popescu"))
        assertNull(FieldCorrections.diff("dentist visit", "Dentist visit"))
    }

    // --- apply: the exact tier ---

    @Test
    fun `apply rewrites every keyed word and preserves whitespace`() {
        val corrections = mapOf("cartosşi" to "cartofi")
        assertEquals(
            "cartofi  prăjiți cu cartofi",
            FieldCorrections.apply("cartosşi  prăjiți cu cartosşi", corrections)
        )
    }

    @Test
    fun `apply matches the punctuation variants of one garble`() {
        val key = VocabularyClassifier.termKey("Denti\$t")
        val corrections = mapOf(key to "Dentist")
        assertEquals("Dentist visit", FieldCorrections.apply("Denti\$t visit", corrections))
        // Same key, different punctuation/casing of the garble.
        assertEquals("Dentist visit", FieldCorrections.apply("denti-\$T visit", corrections))
    }

    @Test
    fun `apply is a no-op on clean text and null`() {
        val corrections = mapOf("cartosşi" to "cartofi")
        assertEquals("cartofi prăjiți", FieldCorrections.apply("cartofi prăjiți", corrections))
        assertNull(FieldCorrections.apply(null, corrections))
    }

    @Test
    fun `apply is idempotent`() {
        val corrections = mapOf("cartosşi" to "cartofi")
        val once = FieldCorrections.apply("cartosşi prăjiți", corrections)
        assertEquals(once, FieldCorrections.apply(once, corrections))
    }

    // --- fuzzyCandidates: the suggestion tier ---

    @Test
    fun `a new garble of a learned fix is a fuzzy hit`() {
        val corrections = mapOf(VocabularyClassifier.termKey("Denti\$t") to "Dentist")
        val hits = FieldCorrections.fuzzyCandidates("Denti appointment", corrections)
        assertEquals(listOf(FieldCorrections.FuzzyHit("Denti", "Dentist")), hits)
    }

    @Test
    fun `an exact-tier word is not also a fuzzy hit`() {
        val key = VocabularyClassifier.termKey("Denti\$t")
        val corrections = mapOf(key to "Dentist")
        assertTrue(FieldCorrections.fuzzyCandidates("Denti\$t visit", corrections).isEmpty())
    }

    @Test
    fun `a word already spelled as the fix is not a hit`() {
        val corrections = mapOf("cartosşi" to "cartofi")
        assertTrue(FieldCorrections.fuzzyCandidates("cartofi prăjiți", corrections).isEmpty())
    }

    @Test
    fun `two candidate fixes decline the word`() {
        val corrections = mapOf(
            "dentista" to "Dentista",
            "dentisto" to "Dentisto"
        )
        assertTrue(FieldCorrections.fuzzyCandidates("dentist", corrections).isEmpty())
    }

    @Test
    fun `two corrections sharing one fix stay a single hit`() {
        val corrections = mapOf(
            "cartosşi" to "cartofi",
            "cart0fi" to "cartofi"
        )
        val hits = FieldCorrections.fuzzyCandidates("cartofl salad", corrections)
        assertEquals(listOf(FieldCorrections.FuzzyHit("cartofl", "cartofi")), hits)
    }

    @Test
    fun `short words never fuzz`() {
        val corrections = mapOf("abc" to "abd")
        assertTrue(FieldCorrections.fuzzyCandidates("ab cd", corrections).isEmpty())
    }
}
