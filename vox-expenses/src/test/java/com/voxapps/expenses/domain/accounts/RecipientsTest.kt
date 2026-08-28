package com.voxapps.expenses.domain.accounts

import com.voxapps.expenses.data.Recipient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipientsTest {

    private fun recipient(
        id: Long,
        name: String,
        iban: String? = null,
        archived: Boolean = false
    ) = Recipient(id = id, name = name, iban = iban, createdAt = 0L, archived = archived)

    @Test
    fun `an IBAN matches exactly, case-insensitively`() {
        val rows = listOf(recipient(1, "Apa", iban = "RO92BBBB2C41007593840001"))
        assertEquals(1L, Recipients.byIban("ro92bbbb2c41007593840001", rows)!!.id)
        assertNull(Recipients.byIban("RO49AAAA1B31007593840000", rows))
        assertNull(Recipients.byIban(null, rows))
        assertNull(Recipients.byIban("  ", rows))
    }

    @Test
    fun `an archived row still answers for its IBAN`() {
        val rows = listOf(recipient(1, "Apa", iban = "RO92BBBB2C41007593840001", archived = true))
        assertEquals(1L, Recipients.byIban("RO92BBBB2C41007593840001", rows)!!.id)
    }

    @Test
    fun `a name singles out one active row or nobody`() {
        val one = listOf(recipient(1, "Salubritate Exemplu SRL"))
        assertEquals(1L, Recipients.named("  salubritate exemplu srl ", one)!!.id)

        val twoActive = one + recipient(2, "Salubritate Exemplu SRL")
        assertNull(Recipients.named("Salubritate Exemplu SRL", twoActive))
    }

    @Test
    fun `archived rows neither match by name nor count toward ambiguity`() {
        val rows = listOf(
            recipient(1, "Apa", archived = true),
            recipient(2, "Apa")
        )
        assertEquals(2L, Recipients.named("Apa", rows)!!.id)
        assertNull(Recipients.named("Apa", listOf(recipient(1, "Apa", archived = true))))
    }

    @Test
    fun `fillsIban fills only an empty slot`() {
        assertTrue(Recipients.fillsIban(recipient(1, "Apa"), "RO92BBBB2C41007593840001"))
        assertFalse(Recipients.fillsIban(recipient(1, "Apa", iban = "RO49AAAA1B31007593840000"), "RO92BBBB2C41007593840001"))
        assertFalse(Recipients.fillsIban(recipient(1, "Apa"), null))
        assertFalse(Recipients.fillsIban(recipient(1, "Apa"), " "))
    }

    @Test
    fun `a new recipient is marked auto-created and trimmed`() {
        val made = Recipients.newRecipient("  Apa Canal  ", "  Banca X ", "  RO92BBBB2C41007593840001 ", 42L)
        assertEquals("Apa Canal", made.name)
        assertEquals("Banca X", made.bankName)
        assertEquals("RO92BBBB2C41007593840001", made.iban)
        assertEquals(42L, made.createdAt)
        assertTrue(made.autoCreated)
    }
}
