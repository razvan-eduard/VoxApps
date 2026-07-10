package com.voxapps.expenses.domain.llm

import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationExpenseParsePromptBuilderTest {

    @Test
    fun `includes the notification title and text`() {
        val prompt = NotificationExpenseParsePromptBuilder.build(
            notificationTitle = "Card payment",
            notificationText = "You paid 45.90 RON at Lidl",
            existingCategories = listOf("Mancare"),
            defaultCurrency = "RON",
            languageCode = "ro"
        )

        assertTrue(prompt.contains("Card payment"))
        assertTrue(prompt.contains("You paid 45.90 RON at Lidl"))
        assertTrue(prompt.contains("Mancare"))
        assertTrue(prompt.contains("RON"))
        assertTrue(prompt.contains("\"ro\""))
    }

    @Test
    fun `explicitly offers the not-a-payment escape hatch`() {
        val prompt = NotificationExpenseParsePromptBuilder.build(null, "promo text", emptyList(), "RON", "en")
        assertTrue(prompt.contains("\"isPayment\": false"))
    }

    @Test
    fun `asks for JSON-only output`() {
        val prompt = NotificationExpenseParsePromptBuilder.build("t", "x", emptyList(), "RON", "en")
        assertTrue(prompt.contains("JSON"))
        assertTrue(prompt.contains("no markdown"))
        assertTrue(prompt.contains("\"totalAmount\""))
    }
}
