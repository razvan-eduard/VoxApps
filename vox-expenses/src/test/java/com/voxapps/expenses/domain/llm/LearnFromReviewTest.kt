package com.voxapps.expenses.domain.llm

import com.voxapps.expenses.data.FieldVocabularies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a review entry may teach, and what it must not.
 *
 * The entry is the one moment the evidence and a person are in the same place: the message could not
 * be read, and the words that would have read it are sitting on screen. Everything here is about
 * keeping that offer honest — only words the lists genuinely lack, only on approval, and never a
 * word that would break the lists it joins.
 */
class LearnFromReviewTest {

    private val banks = listOf("ING", "Revolut")
    private val legal = listOf("SRL", "PFA")
    private val vendors = listOf("SHOP RO-490")

    /** What the screen asks: is this word worth offering at all? */
    private fun offered(term: String?, own: List<String>, other: List<String>): String? =
        term?.takeIf { FieldVocabularies.rejectionFor(it, own = own, other = other) == null }

    @Test
    fun `an unlisted issuer is worth asking about`() {
        assertEquals("SchemeCard", offered("SchemeCard", own = banks, other = legal + vendors))
    }

    /** A bank already listed has nothing to teach — the capture that named it resolved by itself. */
    @Test
    fun `an issuer already listed is not offered`() {
        assertNull(offered("ING", own = banks, other = legal + vendors))
        assertNull("however it is spelled", offered("i.n.g.", own = banks, other = legal + vendors))
    }

    @Test
    fun `a shop already named is not offered again`() {
        assertNull(offered("SHOP RO-490", own = vendors, other = banks + legal))
    }

    /**
     * The load-bearing refusal. Three lists give three ways to put one word in two of them, and the
     * loader treats overlapping lists as unusable — so an offer accepted here could switch the whole
     * vocabulary off, with nothing on screen to say why.
     */
    @Test
    fun `a word belonging to another list is never offered`() {
        assertNull("a company marker is not an issuer", offered("SRL", own = banks, other = legal + vendors))
        assertNull("a named shop is not an issuer", offered("SHOP RO-490", own = banks, other = legal + vendors))
        assertNull("an issuer is not a shop", offered("ING", own = vendors, other = banks + legal))
    }

    @Test
    fun `nothing to offer where the capture resolved`() {
        assertNull(offered(null, own = banks, other = legal))
    }

    /** Both lists stay disjoint after everything a review could add. */
    @Test
    fun `what a review can teach keeps the lists apart`() {
        val learnedBank = offered("SchemeCard", own = banks, other = legal + vendors)
        val learnedVendor = offered("CORNER MARKET", own = vendors, other = banks + legal)
        val allBanks = banks + listOfNotNull(learnedBank)
        val allVendors = vendors + listOfNotNull(learnedVendor)
        val clash = FieldVocabularies.keysOf(allBanks)
            .intersect(FieldVocabularies.keysOf(allVendors) + FieldVocabularies.keysOf(legal))
        assertTrue("overlap would make the whole vocabulary unusable: $clash", clash.isEmpty())
    }
}
