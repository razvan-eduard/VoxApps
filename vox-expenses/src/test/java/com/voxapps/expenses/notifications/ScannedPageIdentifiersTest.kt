package com.voxapps.expenses.notifications

import com.voxapps.textmatch.extract.AccountIdentifiers
import com.voxapps.textmatch.extract.CurrencyCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a scanned page says about the card, the account and the currency.
 *
 * The other half of the reading, and the half where an IBAN actually turns up: a notification
 * carries a masked tail and almost never an account number, while a receipt footer, an invoice and
 * a statement print one in full. Both go through the same reader on the same whole-page text — see
 * ExpenseScanCleanupRequestSender.accountFrom — so what is asserted here is what the scan path does.
 */
class ScannedPageIdentifiersTest {

    /** A card slip's footer, as a scan hands it over: several lines, the tail masked, the total
     *  printed above it. */
    private val cardSlip = """
        LIDL DISCOUNT SRL
        BON FISCAL
        TOTAL                 315,07 LEI
        CARD **** **** **** 4535
        APROBAT 123456
    """.trimIndent()

    private val invoiceFooter = """
        FACTURA SERIA BG NR 1234
        TOTAL DE PLATA        1.234,56 RON
        Cont: RO49AAAA1B31007593840000
        Banca: ING Bank
    """.trimIndent()

    @Test
    fun `a masked tail on a card slip is the card`() {
        val ref = AccountIdentifiers.single(cardSlip)
        assertEquals(AccountIdentifiers.Kind.CARD_TAIL, ref?.kind)
        assertEquals("4535", ref?.digits)
    }

    @Test
    fun `an iban in an invoice footer is the account`() {
        val ref = AccountIdentifiers.single(invoiceFooter)
        assertEquals(AccountIdentifiers.Kind.IBAN, ref?.kind)
        assertEquals("RO49AAAA1B31007593840000", ref?.digits)
    }

    /** The spellings a printer uses for the same masked tail. */
    @Test
    fun `every way a slip masks a tail reads the same card`() {
        listOf(
            "CARD ************4535",
            "CARD **** **** **** 4535",
            "Card xxxx4535",
            "CARD ••4535",
            "CARD ....4535",
            "CARD ####4535"
        ).forEach { line ->
            assertEquals(line, "4535", AccountIdentifiers.single("TOTAL 10,00 RON\n$line")?.digits)
        }
    }

    /** A page that prints the number in full is read as the card it is, not as a tail. */
    @Test
    fun `a full card number is a card`() {
        val ref = AccountIdentifiers.single("TOTAL 10,00 RON\nCARD 4539578763621486")
        assertEquals(AccountIdentifiers.Kind.CARD, ref?.kind)
    }

    /** A figure that is not a card number is not read as one — a receipt is full of digits. */
    @Test
    fun `long numbers that are not cards name no account`() {
        assertNull(AccountIdentifiers.single("BON FISCAL 1234567890123456789012\nTOTAL 10,00 RON"))
        assertNull(AccountIdentifiers.single("NR 1234 5678 9012 3456\nTOTAL 10,00 RON"))
    }

    /** A page naming both its account and the card it was paid with names two things, so it names
     *  neither — the same certainty rule the amount follows. */
    @Test
    fun `an account and a card on one page identify neither`() {
        val both = invoiceFooter + "\nPlata cu cardul ••4535"
        assertNull(AccountIdentifiers.single(both))
    }

    // --- the currency, read from the page rather than defaulted ---

    @Test
    fun `a slip printing LEI reads as the leu this install holds`() {
        assertEquals("RON", CurrencyCodes.find(cardSlip, known = setOf("RON")))
    }

    @Test
    fun `a slip printing LEI names nothing when the install holds neither leu`() {
        assertNull(CurrencyCodes.find(cardSlip))
    }

    @Test
    fun `an invoice printing the code needs nothing known`() {
        assertEquals("RON", CurrencyCodes.find(invoiceFooter))
    }

    /** A page that converts prints two currencies, and choosing between them is not a reading. */
    @Test
    fun `a converting page names no currency`() {
        val converted = """
            TOTAL                 45,20 EUR
            Echivalent            224,60 RON
        """.trimIndent()
        assertNull(CurrencyCodes.find(converted, known = setOf("RON", "EUR")))
    }
}
