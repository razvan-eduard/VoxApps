package com.voxapps.expenses.data

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpenseRuleFieldsTest {

    private val windowMillis = TimeUnit.MINUTES.toMillis(2)

    private fun expense(
        title: String? = "Example Store",
        vendor: String? = null,
        totalAmount: Double = 42.0,
        currencyCode: String = "RON",
        direction: TransactionDirection = TransactionDirection.OUTGOING,
        dateTime: Long = 1_000_000L,
        categoryId: Long? = null
    ) = Expense(title = title, vendor = vendor, totalAmount = totalAmount, currencyCode = currencyCode, direction = direction, dateTime = dateTime, categoryId = categoryId)

    private fun fieldById(fields: ExpenseRuleFields, id: String) = fields.all.first { it.id == id }

    @Test
    fun `title field is exact case-insensitive when fuzzy is off`() {
        val fields = ExpenseRuleFields(fuzzyMatchEnabled = false, timeWindowMillis = windowMillis)
        val titleField = fieldById(fields, ExpenseRuleFields.ID_TITLE)
        val existing = expense(title = "Example Store")

        assertTrue(titleField.matches(expense(title = "example store"), existing))
        assertFalse(titleField.matches(expense(title = "Payment to Example Store"), existing))
    }

    @Test
    fun `title field accepts containment when fuzzy is on`() {
        val fields = ExpenseRuleFields(fuzzyMatchEnabled = true, timeWindowMillis = windowMillis)
        val titleField = fieldById(fields, ExpenseRuleFields.ID_TITLE)
        val existing = expense(title = "Example Store")

        assertTrue(titleField.matches(expense(title = "Payment to Example Store"), existing))
    }

    @Test
    fun `blank title on either side never matches, even to itself`() {
        val fields = ExpenseRuleFields(fuzzyMatchEnabled = true, timeWindowMillis = windowMillis)
        val titleField = fieldById(fields, ExpenseRuleFields.ID_TITLE)

        assertFalse(titleField.matches(expense(title = null), expense(title = null)))
    }

    @Test
    fun `dateTime field matches within the window and not just outside it`() {
        val fields = ExpenseRuleFields(fuzzyMatchEnabled = true, timeWindowMillis = windowMillis)
        val dateField = fieldById(fields, ExpenseRuleFields.ID_DATE_TIME)
        val existing = expense(dateTime = 1_000_000L)

        assertTrue(dateField.matches(expense(dateTime = 1_000_000L + windowMillis), existing))
        assertFalse(dateField.matches(expense(dateTime = 1_000_000L + windowMillis + 1), existing))
    }

    @Test
    fun `amount field is exact equality`() {
        val fields = ExpenseRuleFields(fuzzyMatchEnabled = true, timeWindowMillis = windowMillis)
        val amountField = fieldById(fields, ExpenseRuleFields.ID_TOTAL_AMOUNT)

        assertTrue(amountField.matches(expense(totalAmount = 10.0), expense(totalAmount = 10.0)))
        assertFalse(amountField.matches(expense(totalAmount = 10.0), expense(totalAmount = 10.01)))
    }

    @Test
    fun `category field never matches when either side has no category`() {
        val fields = ExpenseRuleFields(fuzzyMatchEnabled = true, timeWindowMillis = windowMillis)
        val categoryField = fieldById(fields, ExpenseRuleFields.ID_CATEGORY_ID)

        assertTrue(categoryField.matches(expense(categoryId = 7L), expense(categoryId = 7L)))
        assertFalse(categoryField.matches(expense(categoryId = null), expense(categoryId = null)))
    }

    @Test
    fun `all ten expected field ids are registered exactly once`() {
        val fields = ExpenseRuleFields(fuzzyMatchEnabled = true, timeWindowMillis = windowMillis)
        val ids = fields.all.map { it.id }

        assertTrue(ids.toSet().size == ids.size)
        assertTrue(ids.containsAll(listOf(
            ExpenseRuleFields.ID_TITLE, ExpenseRuleFields.ID_VENDOR, ExpenseRuleFields.ID_BANK,
            ExpenseRuleFields.ID_LOCATION, ExpenseRuleFields.ID_COMMENTS, ExpenseRuleFields.ID_TOTAL_AMOUNT,
            ExpenseRuleFields.ID_CURRENCY_CODE, ExpenseRuleFields.ID_CATEGORY_ID, ExpenseRuleFields.ID_DIRECTION,
            ExpenseRuleFields.ID_DATE_TIME
        )))
    }
}
