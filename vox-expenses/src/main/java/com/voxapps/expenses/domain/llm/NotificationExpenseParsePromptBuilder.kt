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
        // When the source app is already a known/starred bank, the bank name is deterministic —
        // PaymentNotificationListenerService uses it directly rather than trusting the LLM to echo
        // it back character-for-character (a real, observed failure mode: an otherwise-successful
        // parse with an empty "bank" field). So the model is never even asked for it in that case —
        // one less field to get wrong, and fewer output tokens either way.
        val bankLine: String
        val bankJsonField: String
        if (knownBankName != null) {
            bankLine = """

            The source app for this notification is a known banking app called "$knownBankName" —
            this is certain, not a guess. This does NOT mean this specific notification is a payment —
            banking apps send far more ads, marketing, login alerts, and balance summaries than actual
            transactions, and the same rule above applies: judge this notification on its own content
            alone.
            """.trimIndent()
            bankJsonField = ""
        } else {
            bankLine = ""
            bankJsonField = """, "bank": "..." (omit or use null if the bank isn't known)"""
        }
        return """
            The following is the title and body text of a notification from an app the user has
            marked as a possible source of payment notifications (e.g. a banking or payment app).

            There are two kinds of records this app tracks: OUTGOING transactions (a purchase, payment,
            or transfer where money leaves the user's account) and INCOMING transactions (a refund, an
            incoming transfer, a salary deposit, or an account top-up where money arrives). Both kinds
            are real transactions and must be captured — this is not just about spending.

            First decide whether this specific notification actually describes a completed transaction
            of EITHER kind. Many notifications from such apps are NOT transactions at all — login
            alerts, marketing, promotions, and pure balance-check notifications that report a balance
            and nothing else. If this notification is one of those, respond with exactly
            {"isPayment": false} and nothing else.

            IMPORTANT: a notification that mentions the account's current balance ALONGSIDE a specific
            purchase, payment, or transfer (its own amount and merchant/counterparty) IS still a real
            transaction — the presence of a balance line does not disqualify it on its own. Only reject
            a notification as "not a transaction" when there is no distinct transaction amount or
            counterparty at all, i.e. the ENTIRE notification content is just a balance figure and
            nothing else.
            $bankLine
            If it DOES describe a real transaction, extract it into a structured expense record: infer
            a short title, the vendor/merchant/counterparty name if mentioned, and the transaction
            amount as "totalAmount" (this is required whenever isPayment is true — if you can't find a
            clear amount, treat it as not a payment instead). Use "$defaultCurrency" as the currency
            unless a different one is clearly stated. Also decide "direction": "outgoing" if money left
            the account (a purchase, a payment sent, a transfer sent), or "incoming" if money arrived
            instead (a refund, a transfer received, a salary deposit, an account top-up). Also suggest a
            category based on the content.
            $categoriesLine If one of the existing categories fits, copy that name verbatim,
            character-for-character — never invent a new spelling, translation, capitalization, or
            diacritics for it. Only suggest a new category name if none of the existing ones fit.
            Respond in the "$languageCode" language. Return ONLY a JSON object, no prose, no markdown,
            of the shape {"isPayment": true, "title": "...", "totalAmount": 12.5, "currency": "...",
            "vendor": "...", "direction": "outgoing", "category": "..."$bankJsonField} when it is a
            transaction, or {"isPayment": false} when it is not.

            Notification title: ${notificationTitle.orEmpty()}
            Notification text: ${notificationText.orEmpty()}
        """.trimIndent()
    }
}
