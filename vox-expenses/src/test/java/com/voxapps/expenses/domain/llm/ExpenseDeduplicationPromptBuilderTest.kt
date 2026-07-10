package com.voxapps.expenses.domain.llm

import org.junit.Assert.assertTrue
import org.junit.Test

class ExpenseDeduplicationPromptBuilderTest {

    @Test
    fun `includes expense ids, titles, amounts, and timestamps`() {
        val prompt = ExpenseDeduplicationPromptBuilder.build(
            listOf(
                ExpenseSummary(id = 1, title = "Groceries", vendor = "Lidl", totalAmount = 45.5, currencyCode = "RON", dateTime = 1000L),
                ExpenseSummary(id = 2, title = null, vendor = "Lidl", totalAmount = 45.5, currencyCode = "RON", dateTime = 1005L)
            )
        )

        assertTrue(prompt.contains("id=1"))
        assertTrue(prompt.contains("id=2"))
        assertTrue(prompt.contains("Groceries"))
        assertTrue(prompt.contains("45.5"))
        assertTrue(prompt.contains("RON"))
    }

    @Test
    fun `falls back to vendor when title is missing`() {
        val prompt = ExpenseDeduplicationPromptBuilder.build(
            listOf(ExpenseSummary(id = 1, title = null, vendor = "Kaufland", totalAmount = 10.0, currencyCode = "RON", dateTime = 0L))
        )
        assertTrue(prompt.contains("Kaufland"))
    }

    @Test
    fun `asks for JSON-only output`() {
        val prompt = ExpenseDeduplicationPromptBuilder.build(
            listOf(
                ExpenseSummary(id = 1, title = null, vendor = null, totalAmount = 1.0, currencyCode = "RON", dateTime = 0L),
                ExpenseSummary(id = 2, title = null, vendor = null, totalAmount = 2.0, currencyCode = "RON", dateTime = 0L)
            )
        )
        assertTrue(prompt.contains("JSON"))
        assertTrue(prompt.contains("no markdown"))
        assertTrue(prompt.contains("\"keep\""))
        assertTrue(prompt.contains("\"duplicates\""))
    }
}
