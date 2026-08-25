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
        /** What the page itself proved. Anything settled here leaves the question entirely — the
         *  whole reason the reading runs first is to hand the model less, not to check its work. */
        preParsedTotal: Double? = null,
        preParsedCurrency: String? = null,
        preParsedDate: String? = null,
        preParsedTime: String? = null,
        preParsedVendor: String? = null,
        preParsedBank: String? = null,
        /**
         * Whether to ask for the product line items at all — set from the configured engine's
         * `long_prompt` capability.
         *
         * Off, the whole item half of the prompt goes away rather than being asked for and ignored.
         * An engine that cannot hold the full prompt does not merely fail to return items: the reply
         * collapses into a fragment and the header fields are lost with it, so the record arrives as
         * a stub. Everything not about items is asked identically either way, and the deterministic
         * pre-parse still contributes items of its own where the printed total proves them.
         */
        includeLineItems: Boolean = true
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
            EXTRACT ONLY the vendor name, bank (if printed), total amount${if (includeLineItems) ", and line items" else ""}.
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

        val framingParagraph = """
            The following text was extracted via OCR from a receipt or invoice and may contain
            formatting noise, line-break artifacts, misrecognized characters, or short garbled
            fragments picked up from clutter around the document — identify the receipt's actual
            content and discard anything that clearly isn't part of it. It may also contain one or
            more "--- [photo stitch seam ...] ---" markers: the receipt was photographed as several
            overlapping close-up shots and stitched into one text automatically, and a marker shows
            where two shots were joined. Treat the text as one continuous document across each
            marker — a line item split across a marker, or a word/line duplicated right next to one,
            should still be read as a single item, not two.
            """.trimIndent()
        val ocrTextBlock = "\n\nOCR text: $rawText"

        // Each of these expands to exactly the text that was always here when items are asked for,
        // and to the item-free wording when they are not.
        val itemsClause = if (includeLineItems) ITEMS_CLAUSE else "."
        val itemRules = if (includeLineItems) ITEM_RULES else ""
        // A figure the page proved is not a question. The one exception is a request that also asks
        // for the rows: there the printed total is the anchor the rows are made to sum to, so it
        // stays in the prompt — stated as known rather than asked for.
        val totalAmountRule = when {
            includeLineItems -> TOTAL_RULE_WITH_ITEMS
            preParsedTotal != null -> ""
            else -> TOTAL_RULE_NO_ITEMS
        }
        val currencyJsonField = if (preParsedCurrency != null) "" else """"currency": "...", """
        val totalJsonField = if (preParsedTotal != null && !includeLineItems) "" else """"totalAmount": 12.5, """
        // Written so that a page which proved nothing produces exactly the paragraph this prompt
        // has always produced — it is tuned against real receipts, and a stray space is drift.
        // The two the page states as plainly as its figures: a letterhead and a card slip's issuer.
        // Read from the same lists a message is read by — see CapturedNames — so once either is
        // settled it leaves the question exactly as the total does.
        val vendorClause = if (preParsedVendor != null) "" else ", the vendor/store name"
        val bankClause = if (preParsedBank != null) {
            ""
        } else {
            """,
            the bank if one is printed (e.g. the card-issuing or acquiring bank shown on a POS/card slip,
            such as "ING BANK")"""
        }
        val vendorJsonField = if (preParsedVendor != null) "" else """"vendor": "...", """
        val bankJsonField = if (preParsedBank != null) "" else """"bank": "...", """

        val settled = buildList {
            preParsedTotal?.let {
                if (includeLineItems) {
                    add("""The document's own total is $it — the rows you return must sum to it.""")
                } else {
                    add("""The total is already known with certainty — do NOT extract, guess, or include it.""")
                }
            }
            preParsedCurrency?.let {
                add("""The currency is already known with certainty — do NOT extract, guess, or include it.""")
            }
            preParsedVendor?.let {
                add("""The vendor is already known with certainty — do NOT extract, guess, or include one.""")
            }
            preParsedBank?.let {
                add("""The bank is already known with certainty — do NOT extract, guess, or include one.""")
            }
            if (preParsedCurrency == null) {
                add(
                    """Use "$defaultCurrency" as the currency unless a different one is clearly printed on the
            receipt."""
                )
            }
        }
        val leadIn = if (settled.isEmpty()) "" else settled.joinToString(" ") + " "
        val responseShapeItems = if (includeLineItems) RESPONSE_SHAPE_ITEMS else ""
        val noItemsLine = if (includeLineItems) "" else NO_ITEMS_LINE

        return """
            $framingParagraph

            $instructionBlock

            Extract it into a structured expense record: infer a short title$vendorClause$bankClause, the store's printed location if one appears on the receipt (a city name
            is enough — prefer just the city over a full street address; omit/null if no location is
            printed anywhere, never guess or infer one from the vendor name alone)$itemsClause

$itemRules$totalAmountRule

            ${leadIn}Also suggest a category for this expense based on its content. $categoriesLine
            If one of the existing categories fits, copy that name verbatim, character-for-character —
            never invent a new spelling, translation, capitalization, or diacritics for it.
            Only suggest a new category name if none of the existing ones fit, and only ever suggest
            a short, common-sense label describing the kind of expense (e.g. "Groceries", "Utilities",
            "Transport", "Subscriptions") — never a raw fragment, code, abbreviation, or noise copied
            from the OCR text itself. If nothing in the document supports a confident, meaningful
            category, return null rather than guessing.

            Also decide the record's "direction": "outgoing" if this document represents money paid by
            the customer (a normal purchase receipt/invoice — the default assumption for any receipt),
            or "incoming" only if it is clearly a refund, credit note, or reimbursement document instead.

            Respond in the "$languageCode" language.
            Return ONLY a JSON object of the shape {"title": "...", $totalJsonField$currencyJsonField
            $vendorJsonField$bankJsonField"location": "...", "category": "...", "date": "YYYY-MM-DD",
            "time": "HH:mm", "direction": "outgoing"$responseShapeItems}, no prose,
            no markdown. Omit/null "bank" and "location" if not printed — never guess either.$noItemsLine$ocrTextBlock
        """.trimIndent()
    }

    // The item-dependent fragments, carrying the indentation the surrounding template uses so the
    // prompt reads exactly as it always did wherever items are still asked for.

    private const val ITEMS_CLAUSE = """, and the individual
            product line items (name, quantity — default 1 if not stated, unit price) if clearly present."""

    private const val RESPONSE_SHAPE_ITEMS = """, "items": [{"name": "...", "quantity": 1,
            "unitPrice": 12.5, "netAmount": null, "vatAmount": null, "grossAmount": null}]"""

    private const val NO_ITEMS_LINE = """
            Do NOT return an "items" array; omit it entirely."""

    private val ITEM_RULES = """            IMPORTANT: list EVERY product line you can identify, in the order they appear, even if two
            lines look identical (same name and price) — a receipt can legitimately print several
            separate line items with the same generic name and price (e.g. different flavors/variants
            of the same product, each scanned as its own "1 x" line, where the receipt only prints a
            generic category name and not the specific variant), and if the text was stitched from
            several overlapping photos it may also genuinely contain the same printed line more than
            once. You cannot reliably tell these cases apart from the text alone — so never guess:
            do NOT merge, deduplicate, sum quantities, or skip a line because it looks like a repeat of
            one you already listed. List it again exactly as printed, as its own separate item with its
            own quantity exactly as printed for that line (usually 1). A human reviews the full list
            afterward and merges anything that's actually a duplicate — that decision is never yours.
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

"""

    private const val TOTAL_RULE_WITH_ITEMS =
        """            For "totalAmount": ALWAYS prefer the receipt's own printed/stated total (the actual total
            amount charged, however labeled — "Total", "Total de plată", "TOTAL LEI", etc.) over any
            arithmetic of your own — printed OCR text for a total is far more reliable than your own
            addition, and the printed items list is sometimes incomplete or misread even when the total
            itself is read correctly. Only compute totalAmount as the sum of the line items' subtotals
            if the receipt genuinely shows no total anywhere. Only return null for totalAmount if the
            receipt shows neither a total nor any items with prices at all.
            (The line-item DISTRIBUTIVE/CUMULATIVE rule above is separate and does not override this —
            always prefer the printed total here.)"""

    private const val TOTAL_RULE_NO_ITEMS =
        """            For "totalAmount": use the receipt's own printed/stated total (the actual total amount
            charged, however labeled — "Total", "Total de plată", "TOTAL LEI", etc.) exactly as printed.
            Never add anything up yourself: printed OCR text for a total is far more reliable than your
            own arithmetic. Return null for totalAmount only if the receipt shows no total at all."""
}
