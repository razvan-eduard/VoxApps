package com.voxapps.expenses.domain.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpenseAmountMismatchTest {

    @Test
    fun `isMismatch is false for an exact match`() {
        assertFalse(ExpenseAmountMismatch.isMismatch(33.99, 33.99))
    }

    @Test
    fun `isMismatch tolerates currency rounding within the default tolerance`() {
        assertFalse(ExpenseAmountMismatch.isMismatch(33.99, 33.985))
    }

    @Test
    fun `isMismatch flags a difference beyond the default tolerance`() {
        assertTrue(ExpenseAmountMismatch.isMismatch(33.99, 30.00))
    }

    @Test
    fun `isMismatch never flags when itemsSum is zero`() {
        assertFalse(ExpenseAmountMismatch.isMismatch(33.99, 0.0))
    }

    @Test
    fun `isGrossMismatch is false for an exact match`() {
        assertFalse(ExpenseAmountMismatch.isGrossMismatch(33.99, 33.99))
    }

    @Test
    fun `isGrossMismatch tolerates a tip or discount within the relative band`() {
        // 100 total, 88 in items (12% difference — under the 20% band).
        assertFalse(ExpenseAmountMismatch.isGrossMismatch(100.0, 88.0))
    }

    @Test
    fun `isGrossMismatch flags a distributive price wrongly divided by quantity`() {
        // A "1 BUC X 33.99" line divided by a quantity of 3 by mistake -> unitPrice 11.33,
        // subtotal 33.99 -> but if quantity was actually 3 real units the sum diverges hugely
        // from the printed total. Simulate a 3x understatement.
        assertTrue(ExpenseAmountMismatch.isGrossMismatch(101.97, 33.99))
    }

    @Test
    fun `isGrossMismatch never flags when itemsSum is zero`() {
        assertFalse(ExpenseAmountMismatch.isGrossMismatch(33.99, 0.0))
    }
}
