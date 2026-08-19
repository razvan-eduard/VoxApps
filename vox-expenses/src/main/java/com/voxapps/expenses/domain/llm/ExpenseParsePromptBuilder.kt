package com.voxapps.expenses.domain.llm

import com.voxapps.ipc.VoxSatelliteSchema

/**
 * Language-agnostic semantic prompt builder for spoken expenses.
 * Uses semantic role labeling (verb, subject, object, quantity, price role)
 * before mapping to expense fields.
 */
object ExpenseParsePromptBuilder {
    /**
     * The question, with [VoxSatelliteSchema.INPUT_PLACEHOLDER] where the utterance goes.
     *
     * One shape for both routes: [VoxIpc.OP_GET_SCHEMA] hands it to Commander to cache and fill in
     * locally per command, and when this app is handed the words instead its own flow substitutes
     * them the same way. Whichever route a spoken expense takes, it is asked the same thing.
     */
    fun buildTemplate(existingCategories: List<String>, defaultCurrency: String, languageCode: String): String =
        buildPrompt(VoxSatelliteSchema.INPUT_PLACEHOLDER, existingCategories, defaultCurrency, languageCode)

    private fun buildPrompt(inputText: String, existingCategories: List<String>, defaultCurrency: String, languageCode: String): String {
        val categoriesLine = if (existingCategories.isEmpty()) {
            "No categories exist yet."
        } else {
            "Existing categories: ${existingCategories.joinToString(", ")}."
        }

        return """
            You are a semantic parser for spoken expenses. Do NOT guess numbers.
            First decompose the sentence into its semantic roles, THEN map those
            roles to expense fields. Meaning drives the math, not the other way around.

            STEP 1 — SEMANTIC ROLE LABELING:
            For the sentence, identify these roles (language-agnostic):
              - PREDICATE (verb): the action of acquiring/paying. Confirms a purchase.
              - AGENT (subject): who performs the action (usually the speaker).
              - THEME (object): WHAT was bought — becomes the item name(s).
              - QUANTITY: the count/measure attached to the THEME.
              - PRICE EXPRESSION: the monetary phrase, and CRUCIALLY its role:
                  * DISTRIBUTIVE (per-unit): price applies to ONE unit of the THEME,
                    signaled by a distributive marker in the utterance's language.
                  * CUMULATIVE (total): whole amount spent, no per-unit marker.
              - RECIPIENT/SOURCE (optional): the vendor/store, if mentioned.

            STEP 2 — MAP ROLES TO FIELDS:
              - THEME -> items[].name
              - QUANTITY -> items[].quantity
              - PRICE EXPRESSION:
                  * DISTRIBUTIVE -> items[].unitPrice = that number (copy as-is, NEVER divide)
                  * CUMULATIVE   -> items[].unitPrice = null; number is a subtotal/total
              - RECIPIENT/SOURCE -> vendor

            STEP 3 — ARITHMETIC (only after roles are fixed):
              - If unitPrice known: subtotal = quantity * unitPrice.
              - totalAmount = sum of all item subtotals (or the cumulative amount on fallback).

            STEP 4 — CATEGORIZATION: $categoriesLine Match exactly if possible. Otherwise suggest a
            new one, but only ever a short, common-sense label describing the kind of expense (e.g.
            "Groceries", "Utilities", "Transport", "Subscriptions") — never a raw word fragment or
            noise picked up from misheard speech. If nothing in the input supports a confident,
            meaningful category, return null rather than guessing.

            STEP 5 — DIRECTION: this app tracks two kinds of records: OUTGOING (money the speaker paid
            or spent — the default, and by far the most common case for a spoken expense) and INCOMING
            (money the speaker received — a refund, a repayment, being paid back, a deposit). Set
            "direction" to "outgoing" unless the sentence clearly describes money arriving instead of
            leaving.

            ABSTRACT REASONING PATTERN (schema, not a literal case):
              Given any utterance of the form:
                [PREDICATE] [QUANTITY] [THEME] [PRICE-MARKER] [PRICE] [CURRENCY] [DISTRIBUTIVE-MARKER?]
              Resolve roles independently of specific words:
                - If a DISTRIBUTIVE-MARKER attaches to PRICE
                      => PRICE is the value of ONE [THEME]; unitPrice := PRICE (copy verbatim).
                - Else if PRICE stands alone (CUMULATIVE)
                      => PRICE is the whole amount; unitPrice := null.
              Then, and only then:
                      subtotal := QUANTITY * unitPrice   (when unitPrice known)
                      totalAmount := sum(subtotals)      (else the cumulative PRICE)
              ${DistributiveCumulativeRule.INVARIANT}

            TECHNICAL CONSTRAINTS:
            - Respond in language: "$languageCode".
            - Default currency: "$defaultCurrency".
            - Output: Return ONLY raw JSON. No text, no markdown, no role labels.
            - Format: {"title": "...", "totalAmount": 100.0, "currency": "...", "vendor": "...",
              "category": "...", "date": "YYYY-MM-DD", "time": "HH:mm", "direction": "outgoing",
              "items": [{"name": "...", "quantity": 10.0, "unitPrice": 10.0}]}
            - Extract "date" (YYYY-MM-DD) and "time" (HH:mm, 24h) if mentioned. 
              If not mentioned, leave as null.

            INPUT TEXT:
            $inputText
        """.trimIndent()
    }
}
