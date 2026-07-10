package com.voxapps.expenses.domain.llm

/**
 * Builds the prompt sent to Commander's generic LLM hook after Vision hands back raw OCR text from
 * the "Scan receipt" flow. Mirrors [ExpenseParsePromptBuilder]'s shape and category-verbatim
 * instruction; the difference is the source text is noisy OCR output from a receipt/invoice rather
 * than a clean spoken sentence, so the prompt calls out formatting noise explicitly (same framing
 * vox-notes' NoteScanCleanupPromptBuilder uses for its own OCR text).
 */
object ExpenseScanCleanupPromptBuilder {
    fun build(rawText: String, existingCategories: List<String>, defaultCurrency: String, languageCode: String): String {
        val categoriesLine = if (existingCategories.isEmpty()) {
            "No categories exist yet."
        } else {
            "Existing categories: ${existingCategories.joinToString(", ")}."
        }
        return """
            The following text was extracted via OCR from a receipt or invoice and may contain
            formatting noise, line-break artifacts, misrecognized characters, or short garbled
            fragments picked up from clutter around the document — identify the receipt's actual
            content and discard anything that clearly isn't part of it. Extract it into a structured
            expense record: infer a short title, the vendor/store name, and the individual product
            line items (name, quantity — default 1 if not stated, unit price) if clearly present.
            Compute "totalAmount" as the sum of the line items' subtotals if items were extracted, or
            the receipt's printed total if no items were extracted — only return null for totalAmount
            if the receipt genuinely shows no total or amount at all. Use "$defaultCurrency" as the
            currency unless a different one is clearly printed on the receipt. Also suggest a category
            for this expense based on its content (e.g. a grocery receipt -> a shopping-related
            category). $categoriesLine If one of the existing categories fits, copy that name verbatim,
            character-for-character — never invent a new spelling, translation, capitalization, or
            diacritics for it. Only suggest a new category name if none of the existing ones fit.
            Respond in the "$languageCode" language. Return ONLY a JSON object of the shape
            {"title": "...", "totalAmount": 12.5, "currency": "...", "vendor": "...", "category": "...",
            "items": [{"name": "...", "quantity": 1, "unitPrice": 12.5}]}, no prose, no markdown.

            OCR text: $rawText
        """.trimIndent()
    }
}
