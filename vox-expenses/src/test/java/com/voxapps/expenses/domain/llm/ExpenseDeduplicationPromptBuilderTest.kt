package com.voxapps.expenses.domain.llm

import com.voxapps.expenses.data.TransactionDirection
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpenseDeduplicationPromptBuilderTest {

    @Test
    fun `includes expense ids, titles, amounts, and timestamps`() {
        val prompt = ExpenseDeduplicationPromptBuilder.build(
            listOf(
                ExpenseSummary(id = 1, title = "Groceries", vendor = "Lidl", totalAmount = 45.5, currencyCode = "RON", dateTime = 1000L, direction = TransactionDirection.OUTGOING),
                ExpenseSummary(id = 2, title = null, vendor = "Lidl", totalAmount = 45.5, currencyCode = "RON", dateTime = 1005L, direction = TransactionDirection.OUTGOING)
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
            listOf(ExpenseSummary(id = 1, title = null, vendor = "Kaufland", totalAmount = 10.0, currencyCode = "RON", dateTime = 0L, direction = TransactionDirection.OUTGOING))
        )
        assertTrue(prompt.contains("Kaufland"))
    }

    @Test
    fun `asks for JSON-only output`() {
        val prompt = ExpenseDeduplicationPromptBuilder.build(
            listOf(
                ExpenseSummary(id = 1, title = null, vendor = null, totalAmount = 1.0, currencyCode = "RON", dateTime = 0L, direction = TransactionDirection.OUTGOING),
                ExpenseSummary(id = 2, title = null, vendor = null, totalAmount = 2.0, currencyCode = "RON", dateTime = 0L, direction = TransactionDirection.OUTGOING)
            )
        )
        assertTrue(prompt.contains("JSON"))
        assertTrue(prompt.contains("no markdown"))
        assertTrue(prompt.contains("\"keep\""))
        assertTrue(prompt.contains("\"duplicates\""))
    }

    @Test
    fun `tags each entry with its direction and instructs the model to never cross-group them`() {
        // Reproduces a real report: an incoming top-up and an outgoing payment of the same amount got
        // merged — the model had no way to tell them apart since direction wasn't in the prompt at all.
        val prompt = ExpenseDeduplicationPromptBuilder.build(
            listOf(
                ExpenseSummary(id = 1, title = "Top-up", vendor = null, totalAmount = 1000.0, currencyCode = "RON", dateTime = 0L, direction = TransactionDirection.INCOMING),
                ExpenseSummary(id = 2, title = "Payment", vendor = null, totalAmount = 1000.0, currencyCode = "RON", dateTime = 0L, direction = TransactionDirection.OUTGOING)
            )
        )

        assertTrue(prompt.contains("(incoming)"))
        assertTrue(prompt.contains("(outgoing)"))
        assertTrue(prompt.contains("NEVER group an incoming entry with an outgoing one"))
    }
}
