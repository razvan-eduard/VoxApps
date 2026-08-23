package com.voxapps.expenses.data

import com.voxapps.textmatch.extract.VocabularyClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a device is actually classified against: the supplied list, less what was switched off, plus
 * what was added here.
 *
 * The supplied half is replaced wholesale whenever a newer one arrives, so every test that matters
 * is really about surviving that replacement.
 */
class FieldVocabulariesMergeTest {

    private fun key(term: String) = VocabularyClassifier.termKey(term)

    @Test
    fun `with nothing of its own, the supplied list is what is used`() {
        assertEquals(
            listOf("ING", "Revolut"),
            FieldVocabularies.merge(listOf("ING", "Revolut"), emptySet(), emptySet())
        )
    }

    @Test
    fun `a term added here joins the supplied ones`() {
        val merged = FieldVocabularies.merge(listOf("ING"), setOf("Pluxee"), emptySet())
        assertEquals(listOf("ING", "Pluxee"), merged)
    }

    @Test
    fun `a term switched off is not classified against`() {
        val merged = FieldVocabularies.merge(listOf("ING", "BT"), emptySet(), setOf(key("BT")))
        assertEquals(listOf("ING"), merged)
    }

    /**
     * The reason the blacklist holds keys and not positions: a word switched off against one list
     * must stay off when a different list arrives carrying it. Keyed any other way, an update would
     * quietly reinstate a word someone had refused.
     */
    @Test
    fun `a word stays switched off when a different list arrives carrying it`() {
        val disabled = setOf(key("BT"))
        val before = FieldVocabularies.merge(listOf("ING", "BT"), emptySet(), disabled)
        val after = FieldVocabularies.merge(listOf("BCR", "BT", "Revolut"), emptySet(), disabled)
        assertFalse("BT" in before)
        assertFalse("the newer list must not reinstate it", "BT" in after)
        assertEquals(listOf("BCR", "Revolut"), after)
    }

    /** And the same word spelled differently is the same word — the classifier says so, not a copy of its rules. */
    @Test
    fun `switching off one spelling switches off the other`() {
        val merged = FieldVocabularies.merge(listOf("S.R.L."), emptySet(), setOf(key("SRL")))
        assertTrue(merged.isEmpty())
    }

    /** Keeping a word but not using it is a state worth reaching, so suppression reaches one's own
     *  words too. Deleting is the separate, final act. */
    @Test
    fun `a term added here can also be switched off`() {
        val merged = FieldVocabularies.merge(emptyList(), setOf("Pluxee"), setOf(key("Pluxee")))
        assertTrue(merged.isEmpty())
    }

    @Test
    fun `switching a whole section off leaves the other one alone`() {
        val provided = listOf("ING", "BCR")
        val custom = setOf("Pluxee")
        val allSupplied = FieldVocabularies.keysOf(provided)
        assertEquals(listOf("Pluxee"), FieldVocabularies.merge(provided, custom, allSupplied))

        val allMine = FieldVocabularies.keysOf(custom)
        assertEquals(provided, FieldVocabularies.merge(provided, custom, allMine))
    }

    @Test
    fun `switching everything off classifies against nothing`() {
        val provided = listOf("ING")
        val custom = setOf("Pluxee")
        val everything = FieldVocabularies.keysOf(provided) + FieldVocabularies.keysOf(custom)
        assertTrue(FieldVocabularies.merge(provided, custom, everything).isEmpty())
    }

    @Test
    fun `restating a supplied word adds nothing`() {
        val merged = FieldVocabularies.merge(listOf("ING"), setOf("i.n.g."), emptySet())
        assertEquals("one term, in the supplied spelling", listOf("ING"), merged)
    }

    /**
     * The invariant the loader enforces on a file has to survive what a device adds to it: two lists
     * sharing a term make the whole vocabulary unusable, and nothing on screen would say why.
     */
    @Test
    fun `a device's own terms keep the two lists disjoint`() {
        val legal = FieldVocabularies.merge(listOf("SRL", "SA"), setOf("KFT"), emptySet())
        val banks = FieldVocabularies.merge(listOf("ING"), setOf("Pluxee"), emptySet())
        val clash = legal.map(::key).intersect(banks.map(::key).toSet())
        assertTrue("merged lists must not overlap: $clash", clash.isEmpty())
    }

    @Test
    fun `switching every supplied term off leaves only what was added here`() {
        val merged = FieldVocabularies.merge(
            listOf("ING", "BCR"), setOf("Pluxee"), setOf(key("ING"), key("BCR"))
        )
        assertEquals(listOf("Pluxee"), merged)
    }
}

/**
 * What a device is allowed to add. The cross-list check is the one that matters: a term meaning both
 * a company and a bank makes the whole vocabulary unusable, and the screen would have nothing to
 * show for it — every capture would simply stop resolving a vendor.
 */
class VocabularyAdditionTest {

    private val banks = listOf("ING", "Revolut")
    private val legal = listOf("SRL", "PFA")

    @Test
    fun `a new word is accepted`() {
        assertEquals(null, FieldVocabularies.rejectionFor("Pluxee", own = banks, other = legal))
    }

    @Test
    fun `a word already in this list is refused`() {
        assertEquals(
            FieldVocabularies.Rejection.ALREADY_PRESENT,
            FieldVocabularies.rejectionFor("ING", own = banks, other = legal)
        )
    }

    /** However it is spelled — the classifier's key decides, not the characters. */
    @Test
    fun `a restatement of a present word is refused too`() {
        assertEquals(
            FieldVocabularies.Rejection.ALREADY_PRESENT,
            FieldVocabularies.rejectionFor("i.n.g.", own = banks, other = legal)
        )
    }

    @Test
    fun `a word belonging to the other list is refused`() {
        assertEquals(
            FieldVocabularies.Rejection.IN_THE_OTHER_LIST,
            FieldVocabularies.rejectionFor("SRL", own = banks, other = legal)
        )
        assertEquals(
            "and the dotted spelling is the same word",
            FieldVocabularies.Rejection.IN_THE_OTHER_LIST,
            FieldVocabularies.rejectionFor("S.R.L.", own = banks, other = legal)
        )
    }

    @Test
    fun `a word made only of separators is nothing to add`() {
        assertEquals(
            FieldVocabularies.Rejection.EMPTY,
            FieldVocabularies.rejectionFor("  ..  ", own = banks, other = legal)
        )
    }
}
