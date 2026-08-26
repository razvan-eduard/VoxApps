package com.voxapps.textmatch.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LineEntitiesTest {

    private fun kindOf(line: String, country: String? = null) =
        LineEntities.classify(line, country)?.kind

    private fun valueOf(line: String, country: String? = null) =
        LineEntities.classify(line, country)?.value

    // --- the card the feature is for ---

    @Test
    fun `a business card reads line by line`() {
        assertEquals(LineEntities.Kind.GENERIC, kindOf("Ion Popescu"))
        assertEquals(LineEntities.Kind.PHONE, kindOf("M: 0744 123 456"))
        assertEquals(LineEntities.Kind.PHONE, kindOf("T: +40 21 345 6789"))
        assertEquals(LineEntities.Kind.PHONE, kindOf("0722111222", country = "ro"))
        assertEquals(LineEntities.Kind.EMAIL, kindOf("office@firma.ro"))
        assertEquals(LineEntities.Kind.URL, kindOf("www.firma.ro"))
        assertEquals(LineEntities.Kind.ADDRESS, kindOf("Str. Victoriei 12, București"))
    }

    // --- email ---

    @Test
    fun `an email is the address, not the line`() {
        assertEquals("ana.pop@example.com", valueOf("Email: ana.pop@example.com"))
        assertEquals("office@firma.ro", valueOf("Scrieți-ne la office@firma.ro."))
    }

    @Test
    fun `an at sign without a dotted domain is not a correspondent`() {
        assertEquals(LineEntities.Kind.GENERIC, kindOf("user@localhost"))
    }

    // --- url ---

    @Test
    fun `a scheme or a www is a web address`() {
        assertEquals("https://firma.ro/preturi", valueOf("https://firma.ro/preturi"))
        assertEquals("https://www.firma.ro", valueOf("Vizitați www.firma.ro."))
    }

    /** `terasa.plaja` is as good a phrase as it is a hostname, so it stays generic — the browser
     *  search a generic line offers still reaches it. */
    @Test
    fun `a bare domain is not claimed`() {
        assertEquals(LineEntities.Kind.GENERIC, kindOf("firma.ro"))
    }

    // --- phone: international ---

    @Test
    fun `an international prefix is evidence on its own`() {
        assertEquals("+40213456789", valueOf("+40 21 345 6789"))
        assertEquals("0040213456789", valueOf("0040 21 345 6789"))
    }

    // --- phone: labelled ---

    @Test
    fun `a telephone label vouches for the digits after it`() {
        assertEquals("0213456789", valueOf("Tel: 021.345.67.89"))
        assertEquals("0744123456", valueOf("Mobil 0744-123-456"))
    }

    @Test
    fun `a labelled date is still a date`() {
        assertEquals(LineEntities.Kind.GENERIC, kindOf("Tel confirmat 12.03.2026"))
    }

    /** The one-letter shorthand asks for more digits than a word: an eight-digit code should not
     *  become a phone number because the line opened with a letter. */
    @Test
    fun `the shorthand label needs a full-length number`() {
        assertEquals("0744123456", valueOf("M: 0744 123 456"))
        assertEquals(LineEntities.Kind.GENERIC, kindOf("M: 20260812"))
    }

    // --- phone: parentheses ---

    @Test
    fun `a parenthesised area code is how nothing else is written`() {
        assertEquals("0213456789", valueOf("(021) 345 67 89"))
    }

    // --- phone: the national fast-path ---

    @Test
    fun `a flat national number needs its country`() {
        assertEquals("0722111222", valueOf("0722111222", country = "ro"))
        assertEquals("0231412345", valueOf("0231 412 345", country = "ro"))
        assertEquals("0612345678", valueOf("06 12 34 56 78", country = "fr"))
        // The same digits with nobody's country stay generic.
        assertEquals(LineEntities.Kind.GENERIC, kindOf("0722111222"))
        assertEquals(LineEntities.Kind.GENERIC, kindOf("0722111222", country = "de"))
    }

    @Test
    fun `a flat run the country does not write stays generic`() {
        // Ten digits, wrong lead: an order number, not a Romanian number.
        assertEquals(LineEntities.Kind.GENERIC, kindOf("1722111222", country = "ro"))
        // Right lead, wrong length.
        assertEquals(LineEntities.Kind.GENERIC, kindOf("07221112", country = "ro"))
    }

    @Test
    fun `a price is never a phone number`() {
        assertEquals(LineEntities.Kind.GENERIC, kindOf("TOTAL 0722111222,50", country = "ro"))
    }

    @Test
    fun `digits inside a code are the code's`() {
        assertEquals(LineEntities.Kind.GENERIC, kindOf("REF0722111222X", country = "ro"))
    }

    // --- account outranks everything its digits could pretend to be ---

    @Test
    fun `an iban line is an account, not a phone`() {
        val read = LineEntities.classify("RO49 AAAA 1B31 0075 9384 0000", country = "ro")
        assertEquals(LineEntities.Kind.ACCOUNT, read?.kind)
        assertEquals("RO49AAAA1B31007593840000", read?.value)
    }

    @Test
    fun `a card number is an account by its checksum`() {
        assertEquals(LineEntities.Kind.ACCOUNT, kindOf("4111 1111 1111 1111"))
    }

    // --- address ---

    @Test
    fun `a street word marks the line an address`() {
        assertEquals(LineEntities.Kind.ADDRESS, kindOf("Strada Mihai Eminescu nr. 4"))
        assertEquals(LineEntities.Kind.ADDRESS, kindOf("Bd. Unirii 10"))
        assertEquals(LineEntities.Kind.ADDRESS, kindOf("Șos. Colentina 5"))
        assertEquals(LineEntities.Kind.ADDRESS, kindOf("12 Rue de la Paix"))
    }

    /** Every invoice numbers itself with one, which is why a bare Nr. is not a street word. */
    @Test
    fun `a number label alone is not an address`() {
        assertEquals(LineEntities.Kind.GENERIC, kindOf("Nr. ord 4521"))
    }

    @Test
    fun `the address value is the whole line`() {
        assertEquals("Str. Victoriei 12, București", valueOf("Str. Victoriei 12, București"))
    }

    // --- generic and nothing ---

    @Test
    fun `anything else is generic, and its value is the line`() {
        val read = LineEntities.classify("Cabinet Medical Individual")
        assertEquals(LineEntities.Kind.GENERIC, read?.kind)
        assertEquals("Cabinet Medical Individual", read?.value)
    }

    @Test
    fun `a blank line is nothing`() {
        assertNull(LineEntities.classify(null))
        assertNull(LineEntities.classify("   "))
    }

    // --- the person's own categories ---

    @Test
    fun `a custom category outranks every built-in`() {
        val awb = LineEntities.Options(
            custom = listOf(LineEntities.CustomCategory("AWB", Regex("""AWB[ :]*\d{8,}""")))
        )
        val read = LineEntities.classify("Colet AWB 4032811223 office@firma.ro", options = awb)
        assertEquals(LineEntities.Kind.CUSTOM, read?.kind)
        assertEquals("AWB", read?.customName)
        assertEquals("AWB 4032811223", read?.value)
    }

    @Test
    fun `a custom pattern that does not match changes nothing`() {
        val awb = LineEntities.Options(
            custom = listOf(LineEntities.CustomCategory("AWB", Regex("""AWB\d{8}""")))
        )
        assertEquals(LineEntities.Kind.EMAIL, LineEntities.classify("office@firma.ro", options = awb)?.kind)
    }

    // --- the fuzzy tiers, each an opt-in ---

    private fun fuzzy(vararg kinds: LineEntities.Kind) = LineEntities.Options(fuzzyKinds = kinds.toSet())

    @Test
    fun `fuzzy phone accepts the grouped run nobody vouched for`() {
        assertEquals(LineEntities.Kind.GENERIC, kindOf("0744 123 456"))
        val read = LineEntities.classify("0744 123 456", options = fuzzy(LineEntities.Kind.PHONE))
        assertEquals(LineEntities.Kind.PHONE, read?.kind)
        assertEquals("0744123456", read?.value)
        // Still not a date, fuzzy or no.
        assertEquals(
            LineEntities.Kind.GENERIC,
            LineEntities.classify("12.03.2026", options = fuzzy(LineEntities.Kind.PHONE))?.kind
        )
    }

    @Test
    fun `fuzzy url claims the bare domain`() {
        assertEquals(
            "https://firma.ro",
            LineEntities.classify("firma.ro", options = fuzzy(LineEntities.Kind.URL))?.value
        )
        assertEquals(LineEntities.Kind.GENERIC, kindOf("firma.ro"))
    }

    @Test
    fun `fuzzy email forgives the comma OCR put in the domain`() {
        val read = LineEntities.classify("office@firma,ro", options = fuzzy(LineEntities.Kind.EMAIL))
        assertEquals(LineEntities.Kind.EMAIL, read?.kind)
        assertEquals("office@firma.ro", read?.value)
        assertEquals(LineEntities.Kind.GENERIC, kindOf("office@firma,ro"))
    }

    @Test
    fun `fuzzy address accepts a numbered place without its street word`() {
        assertEquals(
            LineEntities.Kind.ADDRESS,
            LineEntities.classify("Mihai Eminescu nr. 4", options = fuzzy(LineEntities.Kind.ADDRESS))?.kind
        )
        assertEquals(LineEntities.Kind.GENERIC, kindOf("Mihai Eminescu nr. 4"))
        // Too little around the number to be a place, even fuzzily.
        assertEquals(
            LineEntities.Kind.GENERIC,
            LineEntities.classify("Nr. 4521", options = fuzzy(LineEntities.Kind.ADDRESS))?.kind
        )
    }

    /** There is no fuzzy tier for accounts: a checksum relaxed is not an easier match, it is no
     *  match at all. */
    @Test
    fun `an account is never fuzzed into existence`() {
        val all = LineEntities.Options(fuzzyKinds = LineEntities.Kind.entries.toSet())
        val read = LineEntities.classify("RO49 AAAA 1B31 0075 9384 0001", options = all)
        assertEquals(LineEntities.Kind.GENERIC, read?.kind)
    }

    // --- the line under an address ---

    /** The label the feature met in the wild: the street on one line, the city and postal code on
     *  the next. Position is the evidence; shape only has to not contradict it. */
    @Test
    fun `a city and postal line continues the address above it`() {
        assertEquals(true, LineEntities.looksLikeAddressContinuation("Cluj-Napoca, 400497"))
        assertEquals(true, LineEntities.looksLikeAddressContinuation("400497"))
        assertEquals(true, LineEntities.looksLikeAddressContinuation("Cluj-Napoca"))
        assertEquals(true, LineEntities.looksLikeAddressContinuation("Bl. 2, Sc. A, Ap. 7"))
    }

    @Test
    fun `a line with its own reading does not continue anything`() {
        assertEquals(false, LineEntities.looksLikeAddressContinuation("Tel: 0748 777 222"))
        assertEquals(false, LineEntities.looksLikeAddressContinuation("www.biovita.ro"))
        assertEquals(false, LineEntities.looksLikeAddressContinuation("office@biovita.ro"))
        assertEquals(false, LineEntities.looksLikeAddressContinuation("0748777222"))
        assertEquals(false, LineEntities.looksLikeAddressContinuation("   "))
        assertEquals(false, LineEntities.looksLikeAddressContinuation(null))
    }
}
