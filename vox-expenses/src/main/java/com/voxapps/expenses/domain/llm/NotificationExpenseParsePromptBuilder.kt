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
    /**
     * The fictional vendor name used in the few-shot examples below. Small (1-2B) local models are
     * demonstrably unreliable at obeying "don't copy this" instructions (confirmed on-device: a real
     * notification with a real, distinct merchant name still got this literal example value as its
     * vendor). Since a prompt-level instruction can't be trusted to actually stop the leak, this
     * constant lets [NotificationExpenseParseResultParser] deterministically strip it back out of the
     * model's reply regardless of what the model does — a code-level backstop, not just wording.
     */
    const val EXAMPLE_VENDOR_PLACEHOLDER = "Acme Mart"

    fun build(
        notificationTitle: String?,
        notificationText: String?,
        existingCategories: List<String>,
        defaultCurrency: String,
        languageCode: String,
        knownBankName: String? = null,
        // Defaults to the small-model-tuned variant (see EXAMPLE_VENDOR_PLACEHOLDER's doc) since a
        // failed/timed-out capability probe (see VoxCapabilityClient.EngineCapabilities.local) should
        // pick the more defensive prompt, not assume a capable remote model that isn't actually there.
        isLocalEngine: Boolean = true,
        // Fields already resolved deterministically (see NotificationPreParse) are removed from the
        // model's job entirely — same discipline as the scan prompt's date/total bypass: a field
        // the reply must not carry is a field the model cannot invert.
        preParsedAmount: Double? = null,
        preParsedVendor: String? = null
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
        // Few-shot examples matter a lot more than prose rules for a small (1-2B parameter) local
        // model — confirmed on-device: this task's original prose-only prompt reliably missed real
        // transactions whose notification title didn't itself name the merchant (the amount/merchant
        // living only in the body text), and separately misclassified an account top-up as "not a
        // payment" despite the prose already saying top-ups count. Concrete examples anchor both of
        // those cases directly instead of relying on the model to generalize from abstract wording.
        // Kept intentionally short — confirmed on-device that growing this prompt further (a 4th
        // example plus a longer anti-copy paragraph) pushed this 1.5B local model into degenerate,
        // near-empty output for every request (rawJson dropping from ~150 real chars to ~12-47 chars
        // of garbage), not just the specific case it was meant to fix. A small model's prompt budget
        // is a real constraint, not just a token-count ceiling — literal example leakage is instead
        // guarded deterministically in code, see [NotificationExpenseParseResultParser].
        //
        // A cloud/remote engine (GPT-4o-mini, Gemini 1.5 Flash) doesn't share these failure modes —
        // it reliably generalizes from the prose rules alone and has no observed tendency to copy
        // example content verbatim — so for isLocalEngine=false this whole block is omitted rather
        // than translated: a shorter, example-free prompt is simply the better prompt for a capable
        // model, not a lesser version of the local one.
        val examples = if (isLocalEngine) {
            """
            Example 1 (outgoing purchase, vendor only in the body text):
            Notification title: Revolut
            Notification text: 🛒 You spent 45,20 RON at $EXAMPLE_VENDOR_PLACEHOLDER
            RON balance: 900,00 RON
            Output: {"isPayment": true, "title": "$EXAMPLE_VENDOR_PLACEHOLDER", "totalAmount": 45.2, "currency": "RON", "vendor": "$EXAMPLE_VENDOR_PLACEHOLDER", "direction": "outgoing", "category": "Groceries"}

            Example 2 (incoming top-up — still a real transaction, not spending):
            Notification title: Revolut
            Notification text: Auto top-up of RON500 has been added to your account
            Output: {"isPayment": true, "title": "Account top-up", "totalAmount": 500, "currency": "RON", "vendor": "Revolut", "direction": "incoming", "category": "Transfer"}

            Example 3 (not a transaction — pure balance/marketing, no distinct amount+counterparty):
            Notification title: Revolut
            Notification text: Your weekly summary is ready. Current balance: 900,00 RON
            Output: {"isPayment": false}
            """.trimIndent()
        } else {
            ""
        }
        val seeExample2 = if (isLocalEngine) " — see Example 2 below" else ""
        val seeExample1 = if (isLocalEngine) ", see Example 1 below" else ""
        val antiCopyClause = if (isLocalEngine) {
            """ (never copy "$EXAMPLE_VENDOR_PLACEHOLDER" from Example 1 below — that's a
            placeholder, not a real name),"""
        } else {
            ""
        }

        // The same never-ask discipline as the bank, per pre-resolved field.
        val vendorClause: String
        val vendorJsonField: String
        val titleJsonField: String
        if (preParsedVendor != null) {
            // With the vendor certain, the title stops being the model's job too: a title invented
            // from the remaining text names whatever is left in it — observed as a record titled
            // after the card — while the vendor plus the model's own category is the title a person
            // would write. Composed at record creation, not asked for.
            vendorClause = """. The vendor is already known with certainty — do NOT extract,
            guess, or include a vendor, and do NOT include a title"""
            vendorJsonField = ""
            titleJsonField = ""
        } else {
            vendorClause = """, the vendor/merchant/counterparty name if this specific notification's own
            text names one$antiCopyClause"""
            vendorJsonField = """, "vendor": "..."""" + ""
            titleJsonField = """"title": "...", """
        }
        val amountClause: String
        val amountJsonField: String
        if (preParsedAmount != null) {
            amountClause = """. The transaction amount is already known with certainty — do NOT
            extract, guess, or include an amount. Still decide isPayment from the content"""
            amountJsonField = ""
        } else {
            amountClause = """ and the transaction amount as "totalAmount" (this is required
            whenever isPayment is true — if you can't find a clear amount, treat it as not a payment
            instead)"""
            amountJsonField = """, "totalAmount": 12.5"""
        }

        return """
            The following is the title and body text of a notification from an app the user has
            marked as a possible source of payment notifications (e.g. a banking or payment app).

            There are two kinds of records this app tracks: OUTGOING transactions (a purchase, payment,
            or transfer where money leaves the user's account) and INCOMING transactions (a refund, an
            incoming transfer, a salary deposit, or an account top-up where money arrives). Both kinds
            are real transactions and must be captured — this is not just about spending. A top-up,
            auto-top-up, or "added to your account" message IS an incoming transaction, never
            {"isPayment": false}$seeExample2.

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
            nothing else. The merchant/counterparty name is often only in the notification TEXT, not the
            title — read both carefully before deciding$seeExample1.
            $bankLine
            If it DOES describe a real transaction, extract it into a structured expense record: infer
            a short title$vendorClause$amountClause. Use "$defaultCurrency" as the currency
            unless a different one is clearly stated. Also decide "direction": "outgoing" if money left
            the account (a purchase, a payment sent, a transfer sent), or "incoming" if money arrived
            instead (a refund, a transfer received, a salary deposit, an account top-up). Also suggest a
            category based on the content.
            $categoriesLine If one of the existing categories fits, copy that name verbatim,
            character-for-character — never invent a new spelling, translation, capitalization, or
            diacritics for it. Only suggest a new category name if none of the existing ones fit.
            Respond in the "$languageCode" language. Return ONLY a JSON object, no prose, no markdown,
            of the shape {"isPayment": true, $titleJsonField"currency": "..."$amountJsonField$vendorJsonField,
            "direction": "outgoing", "category": "..."$bankJsonField} when it is a
            transaction, or {"isPayment": false} when it is not.

            $examples

            Now classify this actual notification the same way:
            Notification title: ${notificationTitle.orEmpty()}
            Notification text: ${notificationText.orEmpty()}
        """.trimIndent()
    }
}
