package com.voxapps.expenses.data

import org.junit.Assert.assertTrue
import org.junit.Test

class ExpenseDataScoreTest {

    private fun expense(
        source: ExpenseSource = ExpenseSource.MANUAL,
        manuallyEdited: Boolean = false,
        vendor: String? = null,
        bank: String? = null,
        location: String? = null,
        comments: String? = null,
        categoryId: Long? = null
    ) = Expense(
        title = "Groceries", totalAmount = 42.0, currencyCode = "RON", dateTime = 1000L,
        source = source, manuallyEdited = manuallyEdited,
        vendor = vendor, bank = bank, location = location, comments = comments, categoryId = categoryId
    )

    @Test
    fun `source trust tier ordering is manual greater than scan greater than notification greater than voice`() {
        assertTrue(expense(source = ExpenseSource.MANUAL).dataScore() > expense(source = ExpenseSource.SCAN).dataScore())
        assertTrue(expense(source = ExpenseSource.SCAN).dataScore() > expense(source = ExpenseSource.NOTIFICATION).dataScore())
        assertTrue(expense(source = ExpenseSource.NOTIFICATION).dataScore() > expense(source = ExpenseSource.VOICE).dataScore())
    }

    @Test
    fun `more complete fields outrank a sparser record at the same source tier`() {
        val complete = expense(source = ExpenseSource.VOICE, vendor = "Lidl", bank = "ING", location = "Cluj")
        val sparse = expense(source = ExpenseSource.VOICE)
        assertTrue(complete.dataScore() > sparse.dataScore())
    }

    @Test
    fun `manually edited voice record outranks an unedited manual record`() {
        val editedVoice = expense(source = ExpenseSource.VOICE, manuallyEdited = true)
        val uneditedManual = expense(source = ExpenseSource.MANUAL, vendor = "Lidl", bank = "ING", location = "Cluj", comments = "note")
        assertTrue(editedVoice.dataScore() > uneditedManual.dataScore())
    }
}
