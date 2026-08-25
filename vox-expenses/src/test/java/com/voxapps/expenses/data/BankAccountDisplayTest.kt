package com.voxapps.expenses.data

import com.voxapps.textmatch.extract.AccountIdentifiers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The two lines a row shows in a list: what it is called, and the number it is checked against. */
class BankAccountDisplayTest {

    private fun account(
        bankName: String? = null, label: String? = null, digits: String? = null,
        kind: AccountIdentifiers.Kind? = null, parentId: Long? = null, currency: String = "RON"
    ) = BankAccount(
        id = 1, digits = digits, kind = kind?.name, parentId = parentId,
        label = label, currencyCode = currency, bankName = bankName, createdAt = 0L
    )

    @Test
    fun `an account names its bank and the currency it is in, with the IBAN under it`() {
        val ing = account(bankName = "ING", digits = "RO49AAAA1B31007593840000", kind = AccountIdentifiers.Kind.IBAN)
        assertEquals("ING (RON)", ing.title())
        assertEquals("RO49AAAA1B31007593840000", ing.subtitle())
    }

    /** The case the whole change exists for: a bank named by a message that carried no number is an
     *  account, and it has a first line like any other. */
    @Test
    fun `an account with no number still has a name and a currency`() {
        val ing = account(bankName = "ING")
        assertEquals("ING (RON)", ing.title())
        assertNull(ing.subtitle())
    }

    @Test
    fun `what you called it wins over the bank's own name`() {
        assertEquals("Salariu (RON)", account(bankName = "ING", label = "Salariu").title())
    }

    @Test
    fun `a card shows its alias and its number behind a mask`() {
        val card = account(label = "Cardul meu", digits = "4535", kind = AccountIdentifiers.Kind.CARD_TAIL, parentId = 9L)
        assertEquals("Cardul meu", card.title())
        assertEquals("••4535", card.subtitle())
    }

    /** No currency on the first line for a card: it spends its account's money, and its account is
     *  the row that says which currency that is. */
    @Test
    fun `a card does not repeat its account's currency`() {
        val card = account(bankName = "ING", digits = "4535", kind = AccountIdentifiers.Kind.CARD_TAIL, parentId = 9L)
        assertEquals("ING", card.title())
        assertEquals("••4535", card.subtitle())
    }

    /** A card nobody named is known by its number, and the second line would only say it twice. */
    @Test
    fun `an unnamed card says its number once`() {
        val card = account(digits = "4535", kind = AccountIdentifiers.Kind.CARD_TAIL, parentId = 9L)
        assertEquals("••4535", card.title())
        assertNull(card.subtitle())
    }
}
