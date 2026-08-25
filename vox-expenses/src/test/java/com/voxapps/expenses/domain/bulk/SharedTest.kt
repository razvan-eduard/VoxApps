package com.voxapps.expenses.domain.bulk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedTest {

    private data class Row(val bank: String?, val categoryId: Long?)

    @Test
    fun `one value across all of them is that value`() {
        val shared = Shared.across(listOf(Row("ING", 1L), Row("ING", 2L))) { it.bank }
        assertTrue(shared.agreed)
        assertEquals("ING", shared.value)
    }

    /** The case the whole type exists for: disagreement has to be legible as disagreement, not as
     *  an empty field somebody might then fill in without meaning to. */
    @Test
    fun `two values agree on nothing and offer nothing`() {
        val shared = Shared.across(listOf(Row("ING", 1L), Row("Revolut", 1L))) { it.bank }
        assertFalse(shared.agreed)
        assertNull(shared.value)
    }

    @Test
    fun `all empty is agreement that there is nothing`() {
        val shared = Shared.across(listOf(Row(null, null), Row(null, null))) { it.bank }
        assertTrue(shared.agreed)
        assertNull(shared.value)
    }

    /** Empty against a value is still disagreement — one record says ING and the other says
     *  nothing, which is not the same as both saying ING. */
    @Test
    fun `a value against an empty one is a disagreement`() {
        val shared = Shared.across(listOf(Row("ING", null), Row(null, null))) { it.bank }
        assertFalse(shared.agreed)
    }

    @Test
    fun `one record agrees with itself`() {
        assertEquals(4L, Shared.across(listOf(Row("ING", 4L))) { it.categoryId }.value)
    }

    @Test
    fun `no records contradict nothing`() {
        val shared = Shared.across(emptyList<Row>()) { it.bank }
        assertTrue(shared.agreed)
        assertNull(shared.value)
    }
}
