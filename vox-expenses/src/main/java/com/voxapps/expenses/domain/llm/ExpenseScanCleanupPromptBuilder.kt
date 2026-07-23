package com.voxapps.expenses.domain.llm

/**
 * Builds the prompt sent to Commander's generic LLM hook after Vision hands back raw OCR text from
 * the "Scan receipt" flow. Mirrors [ExpenseParsePromptBuilder]'s shape and category-verbatim
 * instruction; the difference is the source text is noisy OCR output from a receipt/invoice rather
 * than a clean spoken sentence, so the prompt calls out formatting noise explicitly (same framing
 * vox-notes' NoteScanCleanupPromptBuilder uses for its own OCR text).
 */
object ExpenseScanCleanupPromptBuilder {
    fun build(
        rawText: String,
        existingCategories: List<String>,
        defaultCurrency: String,
        languageCode: String,
        preParsedDate: String? = null,
        preParsedTime: String? = null
    ): String {
        val categoriesLine = if (existingCategories.isEmpty()) {
            "No categories exist yet."
        } else {
            "Existing categories: ${existingCategories.joinToString(", ")}."
        }

        // Branch 1: We already have deterministic metadata from local Regex.
        // We DON'T even mention searching for date/time to the LLM to prevent hallucinations.
        val bypassMetadata = preParsedDate != null && preParsedTime != null
        
        val instructionBlock = if (bypassMetadata) {
            """
            EXTRACT ONLY the vendor name, bank (if printed), total amount, and line items.
            CRITICAL: DO NOT search for, extract, or guess the transaction date or time. 
            I already have them. Return ONLY the structural data requested below.
            """.trimIndent()
        } else {
            // Branch 2: Regex failed. LLM must do the full extraction.
            """
            Extract the structural data, INCLUDING the transaction date (YYYY-MM-DD) 
            and time (HH:mm, 24h) from the receipt text. 
            Current phone system date is ${java.time.LocalDate.now()}.
            DO NOT suggest any date in the future.
            """.trimIndent()
        }

        return """
            The following text was extracted via OCR from a receipt or invoice and may contain
            formatting noise, line-break artifacts, misrecognized characters, or short garbled
            fragments picked up from clutter around the document — identify the receipt's actual
            content and discard anything that clearly isn't part of it. 
            
            $instructionBlock

            Extract it into a structured expense record: infer a short title, the vendor/store name,
            the bank if one is printed (e.g. the card-issuing or acquiring bank shown on a POS/card slip,
            such as "ING BANK"), the store's printed location if one appears on the receipt (a city name
            is enough — prefer just the city over a full street address; omit/null if no location is
            printed anywhere, never guess or infer one from the vendor name alone), and the individual
            product line items (name, quantity — default 1 if not stated, unit price) if clearly present.
            If a line item's net amount (fără TVA), VAT amount,
            and gross/total amount are ALL separately printed for that line, also include them as
            "netAmount", "vatAmount", "grossAmount" on that item — but only when the document actually
            shows this breakdown explicitly; never compute or estimate these yourself, leave them null otherwise.

            LINE-ITEM PRICE RULE: for each item, the printed price is either DISTRIBUTIVE (the per-unit price
            of ONE item — e.g. a line reading "1 BUC X 33.99" or "3 X 5.00", where the number after the
            multiplication marker IS the value of one unit) or CUMULATIVE (a line/subtotal amount covering the
            whole printed quantity, with no separate per-unit figure shown). Decide which applies to EACH line
            independently:
              - DISTRIBUTIVE: copy that number into "unitPrice" exactly as printed.
              - CUMULATIVE: divide that amount by the item's quantity to get "unitPrice" (quantity * unitPrice
                must reconstruct the printed line amount). Only do this when no distributive per-unit price is
                printed at all for that line.
            ${DistributiveCumulativeRule.INVARIANT}
            This rule governs each line item's "unitPrice" ONLY — it does not apply to "totalAmount" (see the
            rule for that field below, which always prefers the receipt's own printed total).

            For "totalAmount": ALWAYS prefer the receipt's own printed/stated total (the actual total
            amount charged, however labeled — "Total", "Total de plată", "TOTAL LEI", etc.) over any
            arithmetic of your own — printed OCR text for a total is far more reliable than your own
            addition, and the printed items list is sometimes incomplete or misread even when the total
            itself is read correctly. Only compute totalAmount as the sum of the line items' subtotals
            if the receipt genuinely shows no total anywhere. Only return null for totalAmount if the
            receipt shows neither a total nor any items with prices at all.
            (The line-item DISTRIBUTIVE/CUMULATIVE rule above is separate and does not override this —
            always prefer the printed total here.)

            Use "$defaultCurrency" as the currency unless a different one is clearly printed on the
            receipt. Also suggest a category for this expense based on its content. $categoriesLine
            If one of the existing categories fits, copy that name verbatim, character-for-character —
            never invent a new spelling, translation, capitalization, or diacritics for it.
            Only suggest a new category name if none of the existing ones fit.

            Also decide the record's "direction": "outgoing" if this document represents money paid by
            the customer (a normal purchase receipt/invoice — the default assumption for any receipt),
            or "incoming" only if it is clearly a refund, credit note, or reimbursement document instead.

            Respond in the "$languageCode" language.
            Return ONLY a JSON object of the shape {"title": "...", "totalAmount": 12.5, "currency": "...",
            "vendor": "...", "bank": "...", "location": "...", "category": "...", "date": "YYYY-MM-DD",
            "time": "HH:mm", "direction": "outgoing", "items": [{"name": "...", "quantity": 1,
            "unitPrice": 12.5, "netAmount": null, "vatAmount": null, "grossAmount": null}]}, no prose,
            no markdown. Omit/null "bank" and "location" if not printed — never guess either.

            OCR text: $rawText
        """.trimIndent()
    }
}
