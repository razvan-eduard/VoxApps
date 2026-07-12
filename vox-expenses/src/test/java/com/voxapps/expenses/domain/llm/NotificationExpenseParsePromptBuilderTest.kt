package com.voxapps.expenses.domain.llm

import org.junit.Assert.assertFalse
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

    @Test
    fun `no knownBankName means no bank instructions in the prompt`() {
        val prompt = NotificationExpenseParsePromptBuilder.build("t", "x", emptyList(), "RON", "en")
        assertFalse(prompt.contains("known banking app"))
    }

    @Test
    fun `knownBankName tells the LLM the bank authoritatively`() {
        val prompt = NotificationExpenseParsePromptBuilder.build(
            notificationTitle = "Card payment",
            notificationText = "You paid 45.90 RON at Lidl",
            existingCategories = emptyList(),
            defaultCurrency = "RON",
            languageCode = "en",
            knownBankName = "Revolut"
        )

        assertTrue(prompt.contains("known banking app called \"Revolut\""))
        assertTrue(prompt.contains("set \"bank\" to exactly"))
        assertTrue(prompt.contains("\"Revolut\" in your response"))
    }

    @Test
    fun `knownBankName does not override the isPayment judgment - ad notifications must still be discardable`() {
        // Starring an app as a bank must never bias the model into treating every notification
        // from it as a payment (e.g. a marketing/ad notification from a starred banking app).
        val prompt = NotificationExpenseParsePromptBuilder.build(
            notificationTitle = "promo",
            notificationText = "promo text",
            existingCategories = emptyList(),
            defaultCurrency = "RON",
            languageCode = "en",
            knownBankName = "Revolut"
        )

        assertTrue(prompt.contains("\"isPayment\": false"))
        assertTrue(prompt.contains("does NOT mean this specific notification is a payment"))
    }
}
