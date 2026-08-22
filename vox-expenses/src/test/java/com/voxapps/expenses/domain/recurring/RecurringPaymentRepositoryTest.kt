package com.voxapps.expenses.domain.recurring

import com.voxapps.expenses.data.RecurrenceFrequency
import com.voxapps.expenses.data.RecurringPayment
import com.voxapps.expenses.data.RecurringPaymentDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * What the app is allowed to conclude from a repeated payment, and — more to the point — what it is
 * not. Every test here that passes is one where the app declined to decide something on your behalf.
 */
class RecurringPaymentRepositoryTest {

    private class FakeDao : RecurringPaymentDao {
        val rows = mutableListOf<RecurringPayment>()
        private var nextId = 1L
        override fun observeAll(): Flow<List<RecurringPayment>> = flowOf(rows.filter { !it.dismissed })
        override suspend fun confirmed() = rows.filter { it.confirmed && !it.dismissed }
        override suspend fun observeAllOnce() = rows.filter { !it.dismissed }
        override suspend fun allRows() = rows.toList()
        override suspend fun find(vendorKey: String, frequency: RecurrenceFrequency) =
            rows.firstOrNull { it.vendorKey == vendorKey && it.frequency == frequency }
        override suspend fun byId(id: Long) = rows.firstOrNull { it.id == id }
        override suspend fun insert(payment: RecurringPayment): Long {
            val id = nextId++
            rows += payment.copy(id = id)
            return id
        }
        override suspend fun update(payment: RecurringPayment) {
            rows.replaceAll { if (it.id == payment.id) payment else it }
        }
        override suspend fun delete(payment: RecurringPayment) { rows.removeAll { it.id == payment.id } }
    }

    private fun at(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance().apply { clear(); set(year, month - 1, day) }.timeInMillis

    private fun repo() = FakeDao().let { it to RecurringPaymentRepository(it) }

    @Test
    fun `a first payment is remembered, but claims nothing`() = runTest {
        val (dao, repo) = repo()
        val seen = repo.observe("Digi", at(2026, 1, 15), 49.0, "RON", null)
        assertEquals(1, seen?.occurrences)
        assertFalse(seen!!.confirmed)
        assertTrue("one sighting is never a proposal", repo.proposals(threshold = 2).isEmpty())
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun `a payment a month later counts, and reaches the threshold`() = runTest {
        val (_, repo) = repo()
        repo.observe("Digi", at(2026, 1, 15), 49.0, "RON", null)
        val second = repo.observe("Digi", at(2026, 2, 15), 49.0, "RON", null)
        assertEquals(2, second?.occurrences)
        assertEquals(listOf("Digi"), repo.proposals(2).map { it.vendorLabel })
    }

    /** Two trips to the same shop in one month are two trips. */
    @Test
    fun `a second payment in the same month does not count as a repetition`() = runTest {
        val (_, repo) = repo()
        repo.observe("Kaufland", at(2026, 1, 3), 120.0, "RON", null)
        val second = repo.observe("Kaufland", at(2026, 1, 19), 80.0, "RON", null)
        assertEquals("out of rhythm restarts the count", 1, second?.occurrences)
        assertTrue(repo.proposals(2).isEmpty())
    }

    /** The amount is not identity: a bill that goes up is the same bill. */
    @Test
    fun `a changed amount does not break the recurrence`() = runTest {
        val (_, repo) = repo()
        repo.observe("Digi", at(2026, 1, 15), 49.0, "RON", null)
        val second = repo.observe("Digi", at(2026, 2, 15), 59.0, "RON", null)
        assertEquals(2, second?.occurrences)
        assertEquals("and the newest figure is what is expected next", 59.0, second!!.expectedAmount!!, 0.001)
    }

    @Test
    fun `nothing is proposed when the threshold is off`() = runTest {
        val (_, repo) = repo()
        repo.observe("Digi", at(2026, 1, 15), 49.0, "RON", null)
        repo.observe("Digi", at(2026, 2, 15), 49.0, "RON", null)
        assertTrue("zero is the off switch, not propose-everything", repo.proposals(0).isEmpty())
    }

    @Test
    fun `only a person's confirmation makes it predict`() = runTest {
        val (dao, repo) = repo()
        repo.observe("Digi", at(2026, 1, 15), 49.0, "RON", null)
        repo.observe("Digi", at(2026, 2, 15), 49.0, "RON", null)
        assertTrue(PaymentPredictor.predict(dao.confirmed(), at(2026, 3, 16)) { false }.isEmpty())

        repo.confirm(dao.rows.first().id)
        val predicted = PaymentPredictor.predict(dao.confirmed(), at(2026, 3, 16)) { false }
        assertEquals(1, predicted.size)
        assertEquals("Digi", predicted.first().vendorLabel)
    }

    /** A payment that has already arrived this cycle is an expense, not a prediction. */
    @Test
    fun `nothing is predicted once the real payment has landed`() = runTest {
        val (dao, repo) = repo()
        repo.observe("Digi", at(2026, 1, 15), 49.0, "RON", null)
        repo.observe("Digi", at(2026, 2, 15), 49.0, "RON", null)
        repo.confirm(dao.rows.first().id)
        assertTrue(PaymentPredictor.predict(dao.confirmed(), at(2026, 3, 16)) { true }.isEmpty())
    }

    @Test
    fun `a dismissed arrangement is never proposed again`() = runTest {
        val (dao, repo) = repo()
        repo.observe("Digi", at(2026, 1, 15), 49.0, "RON", null)
        repo.observe("Digi", at(2026, 2, 15), 49.0, "RON", null)
        repo.dismiss(dao.rows.first().id)
        assertTrue(repo.proposals(2).isEmpty())
        assertTrue(PaymentPredictor.predict(dao.confirmed(), at(2026, 3, 16)) { false }.isEmpty())
    }

    /**
     * The same evidence in both directions: as many missed cycles as it took sightings to propose,
     * and it is put back to the person — never removed for them.
     */
    @Test
    fun `a confirmed payment that stops is questioned, not deleted`() = runTest {
        val (dao, repo) = repo()
        repo.observe("Digi", at(2026, 1, 15), 49.0, "RON", null)
        repo.observe("Digi", at(2026, 2, 15), 49.0, "RON", null)
        repo.confirm(dao.rows.first().id)

        repo.refreshMissedCycles(at(2026, 3, 25))
        assertEquals(1, dao.rows.first().missedCycles)
        assertTrue("one missed cycle is not enough", repo.stale(2).isEmpty())

        repo.refreshMissedCycles(at(2026, 4, 25))
        assertEquals(listOf("Digi"), repo.stale(2).map { it.vendorLabel })
        assertTrue("and it is still there to be asked about", dao.rows.first().confirmed)
    }

    /** Asking again on a later day must not add a cycle that did not pass. */
    @Test
    fun `counting the misses twice counts them once`() = runTest {
        val (dao, repo) = repo()
        repo.observe("Digi", at(2026, 1, 15), 49.0, "RON", null)
        repo.observe("Digi", at(2026, 2, 15), 49.0, "RON", null)
        repo.confirm(dao.rows.first().id)

        repo.refreshMissedCycles(at(2026, 3, 25))
        repo.refreshMissedCycles(at(2026, 3, 26))
        repo.refreshMissedCycles(at(2026, 3, 27))
        assertEquals(1, dao.rows.first().missedCycles)
    }

    /** A payment that arrives clears the missed run — it did not stop after all. */
    @Test
    fun `an arrival resets a missed run`() = runTest {
        val (dao, repo) = repo()
        repo.observe("Digi", at(2026, 1, 15), 49.0, "RON", null)
        repo.observe("Digi", at(2026, 2, 15), 49.0, "RON", null)
        repo.confirm(dao.rows.first().id)
        repo.refreshMissedCycles(at(2026, 4, 25))
        assertEquals(2, dao.rows.first().missedCycles)

        repo.observe("Digi", at(2026, 3, 15), 49.0, "RON", null)
        assertEquals(0, dao.rows.first().missedCycles)
        assertTrue(repo.stale(2).isEmpty())
    }

    /** A reminder is per bill, not per day the job wakes up. */
    @Test
    fun `a reminder is remembered against the due date it was for`() = runTest {
        val (dao, repo) = repo()
        repo.observe("Digi", at(2026, 1, 15), 49.0, "RON", null)
        val id = dao.rows.first().id
        repo.markReminded(id, at(2026, 2, 15))
        assertEquals(at(2026, 2, 15), dao.rows.first().notifiedForDueAt)
    }

    @Test
    fun `a payment with no vendor is nothing to key on`() = runTest {
        val (dao, repo) = repo()
        assertNull(repo.observe(null, at(2026, 1, 15), 49.0, "RON", null))
        assertNull(repo.observe("   ", at(2026, 1, 15), 49.0, "RON", null))
        assertTrue(dao.rows.isEmpty())
    }
}
