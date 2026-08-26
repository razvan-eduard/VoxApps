package com.voxapps.expenses.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** A stored name this build does not know must degrade to the field default, never crash a query. */
class ExpensesConvertersTest {
    private val converters = ExpensesConverters()

    @Test
    fun `known names round-trip`() {
        assertEquals(TransactionDirection.INCOMING, converters.toDirection(converters.fromDirection(TransactionDirection.INCOMING)))
        assertEquals(ExpenseSource.SCAN, converters.toExpenseSource(converters.fromExpenseSource(ExpenseSource.SCAN)))
    }

    @Test
    fun `a name from a newer build falls back to the default`() {
        assertEquals(TransactionDirection.OUTGOING, converters.toDirection("TRANSFER"))
        assertEquals(ExpenseSource.MANUAL, converters.toExpenseSource("EMAIL"))
    }
}
