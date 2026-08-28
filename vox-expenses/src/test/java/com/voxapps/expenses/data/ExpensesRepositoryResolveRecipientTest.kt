package com.voxapps.expenses.data

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** The one-or-nothing recognition of a slip's counterparty, and the learn gate that covers only
 *  the writes — matching is recognition and always links. */
class ExpensesRepositoryResolveRecipientTest {

    private lateinit var recipientDao: RecipientDao
    private lateinit var repository: ExpensesRepository

    private val known = Recipient(
        id = 5, name = "Salubritate Exemplu SRL", bankName = "BANCA MODEL",
        iban = "RO92BBBB2C41007593840001", createdAt = 0L
    )

    @Before
    fun setup() {
        recipientDao = mockk(relaxed = true)
        repository = ExpensesRepository(
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk<Context>(), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            recipientDao = recipientDao
        )
    }

    @Test
    fun `gate OFF still links by IBAN`() = runTest {
        coEvery { recipientDao.getAll() } returns listOf(known)

        val found = repository.resolveRecipient(
            beneficiary = "whatever OCR wrote", iban = "ro92bbbb2c41007593840001",
            bankName = null, learnEnabled = false
        )

        assertEquals(5L, found!!.id)
        coVerify(exactly = 0) { recipientDao.insert(any()) }
        coVerify(exactly = 0) { recipientDao.update(any()) }
    }

    @Test
    fun `gate OFF still links by name but never backfills the IBAN`() = runTest {
        val nameless = known.copy(iban = null)
        coEvery { recipientDao.getAll() } returns listOf(nameless)

        val found = repository.resolveRecipient(
            beneficiary = "Salubritate Exemplu SRL", iban = "RO92BBBB2C41007593840001",
            bankName = null, learnEnabled = false
        )

        assertEquals(5L, found!!.id)
        assertNull(found.iban)
        coVerify(exactly = 0) { recipientDao.update(any()) }
    }

    @Test
    fun `gate ON backfills an empty IBAN slot on a name match`() = runTest {
        val nameless = known.copy(iban = null, bankName = null)
        coEvery { recipientDao.getAll() } returns listOf(nameless)
        val updated = slot<Recipient>()
        coEvery { recipientDao.update(capture(updated)) } returns Unit

        val found = repository.resolveRecipient(
            beneficiary = "Salubritate Exemplu SRL", iban = "RO92BBBB2C41007593840001",
            bankName = "BANCA MODEL", learnEnabled = true
        )

        assertEquals("RO92BBBB2C41007593840001", found!!.iban)
        assertEquals("RO92BBBB2C41007593840001", updated.captured.iban)
        assertEquals("BANCA MODEL", updated.captured.bankName)
    }

    @Test
    fun `gate ON creates from the slip's own fields`() = runTest {
        coEvery { recipientDao.getAll() } returns emptyList()
        val inserted = slot<Recipient>()
        coEvery { recipientDao.insert(capture(inserted)) } returns 9L

        val made = repository.resolveRecipient(
            beneficiary = "Apa Canal", iban = "RO92BBBB2C41007593840001",
            bankName = "BANCA MODEL", learnEnabled = true
        )

        assertEquals(9L, made!!.id)
        assertEquals("Apa Canal", inserted.captured.name)
        assertEquals(true, inserted.captured.autoCreated)
    }

    @Test
    fun `gate OFF creates nothing`() = runTest {
        coEvery { recipientDao.getAll() } returns emptyList()

        assertNull(
            repository.resolveRecipient(
                beneficiary = "Apa Canal", iban = "RO92BBBB2C41007593840001",
                bankName = null, learnEnabled = false
            )
        )
        coVerify(exactly = 0) { recipientDao.insert(any()) }
    }

    @Test
    fun `a blank beneficiary creates nothing even with the gate on`() = runTest {
        coEvery { recipientDao.getAll() } returns emptyList()

        assertNull(
            repository.resolveRecipient(
                beneficiary = "  ", iban = "RO92BBBB2C41007593840001",
                bankName = null, learnEnabled = true
            )
        )
        coVerify(exactly = 0) { recipientDao.insert(any()) }
    }

    @Test
    fun `an IGNORE-conflict loser re-reads the winner's row`() = runTest {
        coEvery { recipientDao.getAll() } returnsMany listOf(emptyList(), listOf(known))
        coEvery { recipientDao.insert(any()) } returns -1L

        val found = repository.resolveRecipient(
            beneficiary = "Salubritate Exemplu SRL", iban = "RO92BBBB2C41007593840001",
            bankName = null, learnEnabled = true
        )

        assertEquals(5L, found!!.id)
    }
}
