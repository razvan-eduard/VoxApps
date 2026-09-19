package com.voxapps.textmatch.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [TwoFieldPreParse]'s amount resolution: one distinct marked figure is taken outright, two are
 * resolved by document order (a payment notification states the transaction before it states the
 * account's new balance, never the reverse), and three or more is genuine ambiguity that declines.
 */
class TwoFieldPreParseAmountTest {

    private val roles = TwoFieldPreParse.Roles(legalForm = "legalForm", issuer = "bank", namedVendor = "vendor")
    private val vocabularies = listOf(
        VocabularyClassifier.Vocabulary("vendor", emptyList()),
        VocabularyClassifier.Vocabulary("legalForm", emptyList()),
        VocabularyClassifier.Vocabulary("bank", emptyList())
    )

    private fun amount(title: String?, text: String?) =
        TwoFieldPreParse.parse(title, text, vocabularies, roles, setOf("RON")).amount

    @Test
    fun `a single marked figure is taken outright`() {
        assertEquals(37.0, amount("Some Shop", "37,00 RON")!!, 0.0)
    }

    @Test
    fun `two distinct figures on separate lines of one field resolve to the first`() {
        assertEquals(19.83, amount(null, "You spent 19,83 RON\nBalance: 728,49 RON")!!, 0.0)
    }

    @Test
    fun `two distinct figures split across the two fields resolve to the title's`() {
        assertEquals(19.83, amount("You spent 19,83 RON", "Balance: 728,49 RON")!!, 0.0)
    }

    @Test
    fun `a figure repeated alongside one other distinct figure still resolves to the first occurrence`() {
        assertEquals(19.83, amount(null, "You spent 19,83 RON\n19,83 RON was deducted\nBalance: 728,49 RON")!!, 0.0)
    }

    @Test
    fun `three distinct figures is genuine ambiguity and declines`() {
        assertNull(amount(null, "Paid 19,83 RON, fee 2,00 RON\nBalance: 728,49 RON"))
    }

    @Test
    fun `no marked figure at all resolves to nothing`() {
        assertNull(amount("Some Shop", "no currency here"))
    }
}
