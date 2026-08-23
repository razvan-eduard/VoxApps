package com.voxapps.expenses.domain.llm

import com.voxapps.expenses.data.Expense
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pointing a new spelling at a name already accepted, instead of adding it to the list.
 *
 * The list is meant to name shops, not renderings of shops. Every branch code and legal suffix added
 * as its own entry makes it a worse copy of the ledger, so a candidate that resembles a name already
 * accepted is offered as a rename and never as a second entry.
 */
class NameAlreadyKnownTest {

    private val level = 2

    private fun record(vendor: String? = null, bank: String? = null, edited: Boolean = true) =
        Expense(
            title = vendor, totalAmount = 1.0, currencyCode = "RON", dateTime = 0L,
            vendor = vendor, bank = bank, manuallyEdited = edited
        )

    // --- which names may be renamed to ---

    @Test
    fun `a name typed onto a record counts as accepted`() {
        val names = NameAlreadyKnown.vouchedNames(listOf(record(vendor = "Mega Image"))) { it.vendor }
        assertEquals(listOf("Mega Image"), names)
    }

    /**
     * The vendor column also holds whatever a looser rule once wrote there. Renaming a merchant to a
     * transfer sentence would be worse than proposing nothing, so only a hand-edited record vouches.
     */
    @Test
    fun `a record nobody edited does not vouch for its own vendor`() {
        val names = NameAlreadyKnown.vouchedNames(listOf(record(vendor = "Plata catre cont", edited = false))) { it.vendor }
        assertTrue(names.isEmpty())
    }

    @Test
    fun `blank and repeated names do not reach the pool`() {
        val records = listOf(record(vendor = "Mega Image"), record(vendor = "  "), record(vendor = "Mega Image"))
        assertEquals(listOf("Mega Image"), NameAlreadyKnown.vouchedNames(records) { it.vendor })
    }

    @Test
    fun `banks are drawn from their own column`() {
        val records = listOf(record(vendor = "Mega Image", bank = "Banca Transilvania"))
        assertEquals(listOf("Banca Transilvania"), NameAlreadyKnown.vouchedNames(records) { it.bank })
    }

    // --- what resembles what ---

    @Test
    fun `a suffix on an accepted name resolves to it`() {
        assertEquals("Mega Image", NameAlreadyKnown.match("Mega Image SRL", listOf("Mega Image"), level))
    }

    /** And the other way: the list may hold the fuller registered name. */
    @Test
    fun `an accepted name carrying a suffix is reached by the bare one`() {
        assertEquals("Mega Image SRL", NameAlreadyKnown.match("Mega Image", listOf("Mega Image SRL"), level))
    }

    @Test
    fun `a branch code resolves to the shop`() {
        assertEquals("Mega Image", NameAlreadyKnown.match("Mega Image RO-490", listOf("Mega Image"), level))
    }

    @Test
    fun `a typo resolves`() {
        assertEquals("Kaufland", NameAlreadyKnown.match("Kaufand", listOf("Kaufland"), level))
    }

    /** Nothing to rename — the capture simply did not look the name up. */
    @Test
    fun `a name already accepted proposes nothing`() {
        assertNull(NameAlreadyKnown.match("Mega Image", listOf("Mega Image"), level))
        assertNull(NameAlreadyKnown.match("  mega image ", listOf("Mega Image"), level))
    }

    /**
     * A candidate resembling two accepted names has identified neither, and taking the first would
     * be picking by list order.
     */
    @Test
    fun `resembling two accepted names proposes nothing`() {
        assertNull(NameAlreadyKnown.match("Carrefour Market", listOf("Carrefour Market Express", "Carrefour Market Contact"), level))
    }

    @Test
    fun `an unrelated name proposes nothing`() {
        assertNull(NameAlreadyKnown.match("Petrom", listOf("Mega Image", "Kaufland"), level))
        assertNull(NameAlreadyKnown.match(null, listOf("Mega Image"), level))
        assertNull(NameAlreadyKnown.match("   ", listOf("Mega Image"), level))
    }

    @Test
    fun `an empty pool proposes nothing`() {
        assertNull(NameAlreadyKnown.match("Mega Image SRL", emptyList(), level))
    }

    // --- the two pools together, as the capture path combines them ---

    @Test
    fun `a listed term and a vouched record name are both reachable`() {
        val listed = listOf("Kaufland")
        val vouched = NameAlreadyKnown.vouchedNames(listOf(record(vendor = "Mega Image"))) { it.vendor }
        val pool = (listed + vouched).distinct()
        assertEquals("Kaufland", NameAlreadyKnown.match("Kaufland SRL", pool, level))
        assertEquals("Mega Image", NameAlreadyKnown.match("Mega Image RO-490", pool, level))
    }

    /**
     * Level 0 is exact equality, which can never propose a rename: the candidate is either already
     * accepted or unrelated. A caller lowering the level turns the offer off rather than tightening it.
     */
    @Test
    fun `the strictest level proposes nothing at all`() {
        assertNull(NameAlreadyKnown.match("Mega Image SRL", listOf("Mega Image"), 0))
    }
}
