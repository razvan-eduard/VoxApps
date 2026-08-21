package com.voxapps.textmatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The examples the rule editor flashes are shown to someone deciding how much mangling to forgive,
 * so the one thing they must be is true: every one of them has to survive the matcher at the level
 * that produced it. Until this existed, that was a sentence in a comment, and the budget it depended
 * on was written out a second time in the screen that drew them — where nothing would have noticed
 * the two drifting apart.
 */
class FuzzExamplesTest {

    private val words = listOf(
        "Kaufland", "Lidl", "DINI GROUP LIMANU", "ING", "Mega Image", "e-Boda", "OMV Petrom"
    )

    /** The property the whole thing exists for. */
    @Test
    fun `every generated example still matches at its own level`() {
        for (word in words) {
            for (level in 0..3) {
                for (example in FuzzExamples.forLevel(word, level, "%s SRL")) {
                    assertTrue(
                        "level $level offered \"$example\" for \"$word\", which the matcher rejects",
                        FuzzyNameMatcher.namesMatchLeveled(word, example, level)
                    )
                }
            }
        }
    }

    /**
     * And they have to be worth showing. An example identical to the word demonstrates nothing —
     * the exception is level 0, whose whole point is that case does not matter.
     */
    @Test
    fun `examples differ from the word they illustrate`() {
        for (word in words) {
            for (level in 1..3) {
                for (example in FuzzExamples.forLevel(word, level, "%s SRL")) {
                    assertTrue(
                        "level $level offered \"$example\", unchanged from \"$word\"",
                        example != word
                    )
                }
            }
        }
    }

    /**
     * Containment is what the upper levels newly permit, so it is what they show — and the lower
     * ones must not, since there it would be an example that does not match.
     */
    @Test
    fun `only the levels that allow containment demonstrate it`() {
        val word = "Kaufland"
        for (level in 0..3) {
            val shown = FuzzExamples.forLevel(word, level, "%s SRL").any { it.contains("SRL") }
            assertEquals(
                "level $level disagrees with the matcher about containment",
                level >= FuzzyNameMatcher.CONTAINMENT_FROM_LEVEL,
                shown
            )
        }
    }

    /** A trigger field being typed into is empty most of the time; it has nothing to illustrate. */
    @Test
    fun `an empty word yields nothing rather than noise`() {
        assertTrue(FuzzExamples.forLevel("", 2, "%s SRL").isEmpty())
        assertTrue(FuzzExamples.forLevel("   ", 2, "%s SRL").isEmpty())
    }

    /** The budget is the matcher's, not a copy — a level's edits scale with the word's length. */
    @Test
    fun `the edit budget is the matcher's own`() {
        assertEquals(1, FuzzyNameMatcher.editBudget(4, 1))
        assertEquals(FuzzyNameMatcher.editBudget(20, 1) < FuzzyNameMatcher.editBudget(20, 3), true)
    }
}
