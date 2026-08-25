package com.voxapps.expenses.domain.bulk

import com.voxapps.expenses.data.Expense
import com.voxapps.expenses.data.ExpenseOrigins
import com.voxapps.expenses.data.TransactionDirection
import com.voxapps.recordflow.FieldOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BulkEditTest {

    private val record = Expense(
        title = "t", totalAmount = 63.0, currencyCode = "RON", vendor = "Lidl", bank = "ING",
        location = "Cluj", dateTime = 1000L, comments = "c", categoryId = 4L, bankAccountId = 9L,
        direction = TransactionDirection.OUTGOING,
        originsJson = ExpenseOrigins.encode(mapOf(ExpenseOrigins.FIELD_VENDOR to FieldOrigin.ANSWERED))
    )

    @Test
    fun `an empty edit is empty`() {
        assertTrue(BulkEdit().isEmpty)
        assertTrue(!BulkEdit(categoryId = 1L).isEmpty)
    }

    /** The rule the whole feature rests on: what an edit does not name, it does not touch. */
    @Test
    fun `what is not named is left exactly as it was`() {
        val edited = BulkEdit(categoryId = 7L).applyTo(record)
        assertEquals(7L, edited.categoryId)
        assertEquals("Lidl", edited.vendor)
        assertEquals("ING", edited.bank)
        assertEquals("Cluj", edited.location)
        assertEquals(9L, edited.bankAccountId)
        assertEquals(TransactionDirection.OUTGOING, edited.direction)
        // And the facts of the payment are not among the fields it can name at all.
        assertEquals(63.0, edited.totalAmount, 0.001)
        assertEquals(1000L, edited.dateTime)
        assertEquals("RON", edited.currencyCode)
    }

    @Test
    fun `every field it does name is written`() {
        val edited = BulkEdit(
            categoryId = 7L,
            vendor = "Carrefour",
            bank = "Revolut",
            bankAccountId = 3L,
            location = "Oradea",
            direction = TransactionDirection.INCOMING
        ).applyTo(record)

        assertEquals(7L, edited.categoryId)
        assertEquals("Carrefour", edited.vendor)
        assertEquals("Revolut", edited.bank)
        assertEquals(3L, edited.bankAccountId)
        assertEquals("Oradea", edited.location)
        assertEquals(TransactionDirection.INCOMING, edited.direction)
    }

    /** Choosing a value for records you chose is as typed as typing gets, so the fields stop
     *  claiming a document or a model wrote them. */
    @Test
    fun `what it writes becomes the person's own`() {
        val edited = BulkEdit(categoryId = 7L, vendor = "Carrefour").applyTo(record)
        val origins = ExpenseOrigins.decode(edited.originsJson)
        assertEquals(FieldOrigin.TYPED, origins[ExpenseOrigins.FIELD_VENDOR])
        assertEquals(FieldOrigin.TYPED, origins[ExpenseOrigins.FIELD_CATEGORY])
    }

    @Test
    fun `an untouched field keeps whatever it claimed`() {
        val edited = BulkEdit(categoryId = 7L).applyTo(record)
        assertEquals(
            FieldOrigin.ANSWERED,
            ExpenseOrigins.decode(edited.originsJson)[ExpenseOrigins.FIELD_VENDOR]
        )
    }
}
