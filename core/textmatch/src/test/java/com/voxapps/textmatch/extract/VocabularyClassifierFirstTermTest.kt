package com.voxapps.textmatch.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A list of words asked as a yes-or-no question about a text — see [VocabularyClassifier.firstTerm]. */
class VocabularyClassifierFirstTermTest {

    private val stop = listOf("declined", "insufficient funds", "refuzat")

    @Test
    fun `a word in the text is found whatever its case or punctuation`() {
        assertEquals("declined", VocabularyClassifier.firstTerm("Card DECLINED at Lidl", stop))
        assertEquals("declined", VocabularyClassifier.firstTerm("Payment (declined).", stop))
    }

    @Test
    fun `a phrase matches only as the whole phrase`() {
        assertEquals("insufficient funds", VocabularyClassifier.firstTerm("Reason: insufficient funds", stop))
        assertNull(VocabularyClassifier.firstTerm("Sufficient funds available", stop))
    }

    /** Token equality, not substring: a word that merely contains another is a different word. */
    @Test
    fun `a longer word that swallows a listed one does not match`() {
        assertNull(VocabularyClassifier.firstTerm("Undeclined charge", stop))
        assertNull(VocabularyClassifier.firstTerm("refuzata", listOf("refuzat")))
    }

    @Test
    fun `an ordinary payment carries none of them`() {
        assertNull(VocabularyClassifier.firstTerm("63,00 RON with ING Card **00 at Lidl", stop))
    }

    @Test
    fun `nothing to look for, and nothing to look in`() {
        assertNull(VocabularyClassifier.firstTerm("declined", emptyList()))
        assertNull(VocabularyClassifier.firstTerm(null, stop))
        assertNull(VocabularyClassifier.firstTerm("   ", stop))
    }
}
