package com.voxapps.expenses.domain.llm

/**
 * Language-agnostic semantic prompt builder for spoken expenses.
 * Uses semantic role labeling (verb, subject, object, quantity, price role)
 * before mapping to expense fields.
 */
object ExpenseParsePromptBuilder {
    fun build(rawText: String, existingCategories: List<String>, defaultCurrency: String, languageCode: String): String {
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

            STEP 4 — CATEGORIZATION: $categoriesLine Match exactly if possible, or suggest a new one.

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
              Invariant: a DISTRIBUTIVE PRICE is NEVER divided by QUANTITY.

            TECHNICAL CONSTRAINTS:
            - Respond in language: "$languageCode".
            - Default currency: "$defaultCurrency".
            - Output: Return ONLY raw JSON. No text, no markdown, no role labels.
            - Format: {"title": "...", "totalAmount": 100.0, "currency": "...", "vendor": "...", 
              "category": "...", "items": [{"name": "...", "quantity": 10.0, "unitPrice": 10.0}]}

            INPUT TEXT:
            $rawText
        """.trimIndent()
    }
}
