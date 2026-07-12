package com.voxapps.expenses.domain.llm

/**
 * Builds the prompt sent to Commander's generic LLM hook for a captured payment-app notification
 * (see [com.voxapps.expenses.receiver.PaymentNotificationListenerService]). Unlike
 * [ExpenseParsePromptBuilder]/[ExpenseScanCleanupPromptBuilder], the source text here was never
 * confirmed to actually describe a payment — it's an arbitrary notification from an allowlisted app —
 * so the prompt explicitly gives the LLM an escape hatch ("isPayment": false) rather than forcing a
 * guess, and the caller must check that flag before treating the rest of the response as meaningful.
 */
object NotificationExpenseParsePromptBuilder {
    fun build(
        notificationTitle: String?,
        notificationText: String?,
        existingCategories: List<String>,
        defaultCurrency: String,
        languageCode: String,
        knownBankName: String? = null
    ): String {
        val categoriesLine = if (existingCategories.isEmpty()) {
            "No categories exist yet."
        } else {
            "Existing categories: ${existingCategories.joinToString(", ")}."
        }
        val bankLine = if (knownBankName != null) {
            """

            The source app for this notification is a known banking app called "$knownBankName" —
            this is certain, not a guess. This does NOT mean this specific notification is a payment —
            banking apps send far more ads, marketing, login alerts, and balance summaries than actual
            transactions, and the same rule above applies: judge this notification on its own content
            alone. Only if you independently determine it IS a payment, set "bank" to exactly
            "$knownBankName" in your response, character-for-character.
            """.trimIndent()
        } else {
            ""
        }
        return """
            The following is the title and body text of a notification from an app the user has
            marked as a possible source of payment notifications (e.g. a banking or payment app).
            Many notifications from such apps are NOT transactions at all (login alerts, marketing,
            balance summaries, promotions) — first decide whether this specific notification actually
            describes a completed payment/purchase/transfer. If it does not, respond with exactly
            {"isPayment": false} and nothing else.
            $bankLine
            If it DOES describe a real transaction, extract it into a structured expense record: infer
            a short title, the vendor/merchant name if mentioned, and the transaction amount as
            "totalAmount" (this is required whenever isPayment is true — if you can't find a clear
            amount, treat it as not a payment instead). Use "$defaultCurrency" as the currency unless a
            different one is clearly stated. Also suggest a category based on the content.
            $categoriesLine If one of the existing categories fits, copy that name verbatim,
            character-for-character — never invent a new spelling, translation, capitalization, or
            diacritics for it. Only suggest a new category name if none of the existing ones fit.
            Respond in the "$languageCode" language. Return ONLY a JSON object, no prose, no markdown,
            of the shape {"isPayment": true, "title": "...", "totalAmount": 12.5, "currency": "...",
            "vendor": "...", "category": "...", "bank": "..."} when it is a payment, or
            {"isPayment": false} when it is not. Omit "bank" (or use null) if the bank isn't known.

            Notification title: ${notificationTitle.orEmpty()}
            Notification text: ${notificationText.orEmpty()}
        """.trimIndent()
    }
}
