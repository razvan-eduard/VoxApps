package com.voxapps.expenses.domain.llm

/**
 * Builds the prompt sent to Commander's generic LLM hook to turn a raw spoken utterance (passed
 * through verbatim by Commander's NLU as `text` — see the satellite's `nluHint`) into structured
 * expense fields. Mirrors [com.voxapps.expenses.domain.llm]'s other prompt builders in shape; the
 * suggested category is resolved through the same fuzzy-match path voice notes use in vox-notes
 * (`:core:textmatch`'s `FuzzyNameMatcher`) — the LLM's job here is only to guess a name, not to decide
 * the final category. [totalAmount] is the one truly mandatory field on an expense, so the prompt
 * explicitly asks the LLM to compute it (summing any items mentioned) whenever any amount is present
 * in the utterance at all, and to return null only when genuinely no amount was said.
 */
object ExpenseParsePromptBuilder {
    fun build(rawText: String, existingCategories: List<String>, defaultCurrency: String, languageCode: String): String {
        val categoriesLine = if (existingCategories.isEmpty()) {
            "No categories exist yet."
        } else {
            "Existing categories: ${existingCategories.joinToString(", ")}."
        }
        return """
            The following text is a spoken description of a purchase or expense, transcribed from
            voice and possibly containing recognition noise. Extract it into a structured expense
            record: infer a short title, the vendor/store name if mentioned, and any individual
            product line items (name, quantity — default 1 if not stated, unit price). Compute
            "totalAmount" as the sum of the line items' subtotals if items were extracted, or the
            single amount mentioned in the text if no items were extracted — only return null for
            totalAmount if the text genuinely mentions no amount at all. Use "$defaultCurrency" as the
            currency unless a different one is clearly stated or implied (e.g. by a currency symbol or
            name). Also suggest a category for this expense based on its content. $categoriesLine If
            one of the existing categories fits, copy that name verbatim, character-for-character —
            never invent a new spelling, translation, capitalization, or diacritics for it. Only
            suggest a new category name if none of the existing ones fit. Respond in the
            "$languageCode" language. Return ONLY a JSON object of the shape {"title": "...",
            "totalAmount": 12.5, "currency": "...", "vendor": "...", "category": "...",
            "items": [{"name": "...", "quantity": 1, "unitPrice": 12.5}]}, no prose, no markdown.

            Spoken text: $rawText
        """.trimIndent()
    }
}
