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
            expense record: infer a short title, the vendor/store name, the bank if one is printed
            (e.g. the card-issuing or acquiring bank shown on a POS/card slip, such as "ING BANK"), and
            the individual product line items (name, quantity — default 1 if not stated, unit price) if
            clearly present. If a line item's net amount (fără TVA), VAT amount, and gross/total amount
            are ALL separately printed for that line, also include them as "netAmount", "vatAmount",
            "grossAmount" on that item — but only when the document actually shows this breakdown
            explicitly; never compute or estimate these yourself, leave them null otherwise.

            For "totalAmount": ALWAYS prefer the receipt's own printed/stated total (the actual total
            amount charged, however labeled — "Total", "Total de plată", "TOTAL LEI", etc.) over any
            arithmetic of your own — printed OCR text for a total is far more reliable than your own
            addition, and the printed items list is sometimes incomplete or misread even when the total
            itself is read correctly. Only compute totalAmount as the sum of the line items' subtotals
            if the receipt genuinely shows no total anywhere. Only return null for totalAmount if the
            receipt shows neither a total nor any items with prices at all.

            Use "$defaultCurrency" as the currency unless a different one is clearly printed on the
            receipt. Also suggest a category for this expense based on its content (e.g. a grocery
            receipt -> a shopping-related category). $categoriesLine If one of the existing categories
            fits, copy that name verbatim, character-for-character — never invent a new spelling,
            translation, capitalization, or diacritics for it. Only suggest a new category name if none
            of the existing ones fit. Respond in the "$languageCode" language. Return ONLY a JSON object
            of the shape {"title": "...", "totalAmount": 12.5, "currency": "...", "vendor": "...",
            "bank": "...", "category": "...", "items": [{"name": "...", "quantity": 1,
            "unitPrice": 12.5, "netAmount": null, "vatAmount": null, "grossAmount": null}]}, no prose,
            no markdown. Omit/null "bank" if none is printed — never guess one.

            OCR text: $rawText
        """.trimIndent()
    }
}
