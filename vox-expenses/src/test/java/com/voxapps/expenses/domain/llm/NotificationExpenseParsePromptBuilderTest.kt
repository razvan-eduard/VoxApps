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
    fun `knownBankName is context only, not something the LLM is asked to echo back`() {
        // The bank name is deterministic once known (the user starred this exact source app) —
        // PaymentNotificationListenerService/LlmResultReceiver use it directly rather than trusting
        // an LLM echo, so the prompt shouldn't spend tokens asking for "bank" in the JSON reply, or
        // instruct the model to repeat the name back, when it's already known.
        val prompt = NotificationExpenseParsePromptBuilder.build(
            notificationTitle = "Card payment",
            notificationText = "You paid 45.90 RON at Lidl",
            existingCategories = emptyList(),
            defaultCurrency = "RON",
            languageCode = "en",
            knownBankName = "Revolut"
        )

        assertTrue(prompt.contains("known banking app called \"Revolut\""))
        assertFalse(prompt.contains("set \"bank\" to exactly"))
        assertFalse(prompt.contains("\"bank\": \"...\""))
    }

    @Test
    fun `no knownBankName still asks for bank in the JSON shape`() {
        val prompt = NotificationExpenseParsePromptBuilder.build("t", "x", emptyList(), "RON", "en")
        assertTrue(prompt.contains("\"bank\": \"...\""))
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

    @Test
    fun `explains there are two transaction directions and asks for the direction field`() {
        val prompt = NotificationExpenseParsePromptBuilder.build("t", "x", emptyList(), "RON", "en")
        assertTrue(prompt.contains("OUTGOING"))
        assertTrue(prompt.contains("INCOMING"))
        assertTrue(prompt.contains("\"direction\": \"outgoing\""))
    }

    @Test
    fun `a balance line alongside a real transaction does not disqualify the notification`() {
        val prompt = NotificationExpenseParsePromptBuilder.build("t", "x", emptyList(), "RON", "en")
        assertTrue(prompt.contains("does not disqualify it on its own"))
        assertTrue(prompt.contains("ENTIRE notification content is just a balance figure"))
    }

    @Test
    fun `includes few-shot examples covering top-up and merchant-only-in-text cases`() {
        // Confirmed on-device: a small local LLM reliably missed real transactions whose merchant
        // name only appeared in the body text (not the title), and separately misclassified an
        // account top-up as not-a-payment despite the prose already covering both cases — concrete
        // examples were added specifically to anchor these two failure modes.
        val prompt = NotificationExpenseParsePromptBuilder.build("t", "x", emptyList(), "RON", "en")
        assertTrue(prompt.contains("Example 1"))
        assertTrue(prompt.contains("Example 2"))
        assertTrue(prompt.contains("Example 3"))
        assertTrue(prompt.contains("top-up, auto-top-up, or \"added to your account\" message IS an incoming transaction") ||
            prompt.contains("IS an incoming transaction"))
    }

    @Test
    fun `warns the model not to copy the example vendor name, briefly`() {
        // Confirmed on-device: the model leaked the example vendor name into a real notification's
        // parsed vendor. A longer instruction (plus a 4th example) was tried and made things worse —
        // the added prompt length pushed this 1.5B local model into degenerate near-empty output for
        // every request. This one short clause is the prompt-level nudge; the real enforcement is the
        // deterministic strip in NotificationExpenseParseResultParserTest.
        val prompt = NotificationExpenseParsePromptBuilder.build("t", "x", emptyList(), "RON", "en")
        assertTrue(prompt.contains("never copy \"${NotificationExpenseParsePromptBuilder.EXAMPLE_VENDOR_PLACEHOLDER}\""))
    }

    @Test
    fun `defaults to the local-engine variant when isLocalEngine is not specified`() {
        val prompt = NotificationExpenseParsePromptBuilder.build("t", "x", emptyList(), "RON", "en")
        assertTrue(prompt.contains("Example 1"))
    }

    @Test
    fun `remote engine prompt omits the few-shot examples and the anti-copy clause`() {
        // A cloud model (GPT-4o-mini, Gemini 1.5 Flash) doesn't share the small local model's failure
        // modes these exist to work around — the examples and the anti-copy nudge are local-only.
        val prompt = NotificationExpenseParsePromptBuilder.build(
            "t", "x", emptyList(), "RON", "en", isLocalEngine = false
        )
        assertFalse(prompt.contains("Example 1"))
        assertFalse(prompt.contains("Example 2"))
        assertFalse(prompt.contains("Example 3"))
        assertFalse(prompt.contains(NotificationExpenseParsePromptBuilder.EXAMPLE_VENDOR_PLACEHOLDER))
        assertFalse(prompt.contains("never copy"))
    }

    @Test
    fun `remote engine prompt still covers direction, escape hatch, and balance-line rules`() {
        // Only the small-model-specific scaffolding (examples, anti-copy) should differ — the actual
        // content rules (direction, JSON shape, balance-line handling) apply to every engine.
        val prompt = NotificationExpenseParsePromptBuilder.build(
            "t", "x", emptyList(), "RON", "en", isLocalEngine = false
        )
        assertTrue(prompt.contains("OUTGOING"))
        assertTrue(prompt.contains("INCOMING"))
        assertTrue(prompt.contains("\"isPayment\": false"))
        assertTrue(prompt.contains("does not disqualify it on its own"))
        assertTrue(prompt.contains("\"totalAmount\""))
    }
}
