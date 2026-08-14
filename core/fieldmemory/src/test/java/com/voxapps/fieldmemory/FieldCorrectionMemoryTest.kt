package com.voxapps.fieldmemory

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldCorrectionMemoryTest {

    private class FakeDao : LearnedFieldCorrectionDao {
        val rows = mutableMapOf<String, LearnedFieldCorrection>()
        override suspend fun get(garbageKey: String) = rows[garbageKey]
        override suspend fun getActive(threshold: Int) =
            rows.values.filter { !it.quarantined && it.consecutiveCount >= threshold }
        override suspend fun upsert(correction: LearnedFieldCorrection) {
            rows[correction.garbageKey] = correction
        }
        override suspend fun getAll() = rows.values.toList()
        override suspend fun deleteAll() = rows.clear()
    }

    private fun memory(dao: FakeDao) = FieldCorrectionMemory(dao, now = { 1L })

    @Test
    fun `thresholds gate activation`() = runTest {
        val dao = FakeDao()
        val memory = memory(dao)
        repeat(3) { memory.learn(listOf("cartosşi prăjiți"), listOf("cartofi prăjiți")) }
        assertEquals(mapOf("cartosşi" to "cartofi"), memory.activeCorrections(threshold = 1))
        assertEquals(mapOf("cartosşi" to "cartofi"), memory.activeCorrections(threshold = 3))
        assertTrue(memory.activeCorrections(threshold = 5).isEmpty())
    }

    @Test
    fun `agreeing fixes that differ only in styling keep counting`() = runTest {
        val dao = FakeDao()
        val memory = memory(dao)
        memory.learn(listOf("cartosşi"), listOf("Cartofi"))
        memory.learn(listOf("cartosşi"), listOf("cartofi"))
        val row = dao.rows.getValue("cartosşi")
        assertEquals(2, row.consecutiveCount)
        assertEquals("Cartofi", row.fix)
        assertTrue(!row.quarantined)
    }

    @Test
    fun `a different fix quarantines permanently`() = runTest {
        val dao = FakeDao()
        val memory = memory(dao)
        memory.learn(listOf("crtf"), listOf("cartofi"))
        memory.learn(listOf("crtf"), listOf("cartof"))
        assertTrue(dao.rows.getValue("crtf").quarantined)
        // Later agreement never re-activates.
        repeat(5) { memory.learn(listOf("crtf"), listOf("cartofi")) }
        assertTrue(dao.rows.getValue("crtf").quarantined)
        assertTrue(memory.activeCorrections(threshold = 1).isEmpty())
    }

    @Test
    fun `declined diffs teach nothing`() = runTest {
        val dao = FakeDao()
        val memory = memory(dao)
        memory.learn(
            listOf("whole field replaced", "cola 500 ml", null),
            listOf("something else entirely", "cola 330 ml", "new")
        )
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `restore keeps the stronger row and never un-quarantines`() = runTest {
        val dao = FakeDao()
        val memory = memory(dao)
        memory.learn(listOf("cartosşi"), listOf("cartofi"))
        memory.restore(LearnedFieldCorrection("cartosşi", "cartofi", consecutiveCount = 4, quarantined = false, updatedAt = 9L))
        assertEquals(4, dao.rows.getValue("cartosşi").consecutiveCount)

        memory.restore(LearnedFieldCorrection("crtf", "cartofi", consecutiveCount = 2, quarantined = true, updatedAt = 9L))
        memory.learn(listOf("crtf"), listOf("cartofi"))
        assertTrue(dao.rows.getValue("crtf").quarantined)
    }
}
