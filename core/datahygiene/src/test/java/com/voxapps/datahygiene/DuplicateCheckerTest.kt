package com.voxapps.datahygiene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private data class DummyRecord(val id: Long, val amount: Double, val vendor: String?)

private object DummyDuplicateChecker : DuplicateChecker<DummyRecord> {
    override fun isDuplicateOf(candidate: DummyRecord, existing: DummyRecord): Boolean =
        candidate.amount == existing.amount &&
            FieldCleaner.clean(candidate.vendor) == FieldCleaner.clean(existing.vendor)
}

class DuplicateCheckerTest {

    @Test
    fun `finds a duplicate with identical normalized fields, ignoring id`() {
        val existing = listOf(DummyRecord(id = 1, amount = 42.0, vendor = "Carrefour"))
        val candidate = DummyRecord(id = 0, amount = 42.0, vendor = "Carrefour")
        assertEquals(existing[0], DummyDuplicateChecker.findDuplicate(candidate, existing))
    }

    @Test
    fun `treats a null-string vendor and a garbage 'null' vendor as equal after normalization`() {
        val existing = listOf(DummyRecord(id = 1, amount = 10.0, vendor = null))
        val candidate = DummyRecord(id = 0, amount = 10.0, vendor = "null")
        assertEquals(existing[0], DummyDuplicateChecker.findDuplicate(candidate, existing))
    }

    @Test
    fun `no match when a meaningful field differs`() {
        val existing = listOf(DummyRecord(id = 1, amount = 42.0, vendor = "Carrefour"))
        val candidate = DummyRecord(id = 0, amount = 42.0, vendor = "Lidl")
        assertNull(DummyDuplicateChecker.findDuplicate(candidate, existing))
    }

    @Test
    fun `empty existing list never matches`() {
        val candidate = DummyRecord(id = 0, amount = 42.0, vendor = "Carrefour")
        assertNull(DummyDuplicateChecker.findDuplicate(candidate, emptyList()))
    }
}
