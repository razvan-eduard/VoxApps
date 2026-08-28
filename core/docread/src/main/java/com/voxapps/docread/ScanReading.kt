package com.voxapps.docread

import com.voxapps.logging.Logger

private const val TAG = "ScanReading"

/**
 * Everything a scan's text yields on its own, before any model is asked anything: the totals and
 * the line items, each having proved the other.
 *
 * The two are read as **combinations**, not in sequence. A footer template proposes what the
 * document's totals are; an items template proposes what its rows are; and the pair is accepted only
 * when the rows add up to one of those totals to the cent. Neither half can be checked alone — a set
 * of totals with nothing summing to it is a guess, and a set of rows with nothing to compare against
 * is a guess — so the unit of acceptance is the pair, and the search runs until one closes.
 *
 * That is what makes the library grow safely. Templates are tried in file order, strictest first,
 * and a wrong one loses on the arithmetic rather than by being excluded in advance; adding shapes
 * costs coverage of nothing. Where no combination closes, the scan yields no items at all, which
 * remains the important outcome: an empty list is a record a person completes, an invented one is a
 * record they must first notice is wrong.
 */
object ScanReading {

    data class Result(
        val totals: ReceiptTotalRegexParser.Result,
        val items: List<TableItemsPreParse.Item>?,
        /** Which items pattern answered — kept so a vendor's winner can be tried first. */
        val templateId: String?,
        /** Which footer pattern produced the totals the items proved themselves against. */
        val footerTemplateId: String? = null,
        /** Who issued the document, when, under what number — read separately, and never allowed to
         *  affect whether the figures were accepted. */
        val header: HeaderReader.Fields = HeaderReader.Fields()
    )

    fun of(
        rawText: String,
        plainText: String,
        itemTemplates: List<LineItemBattery.Template> = LineItemBattery.BUILT_IN,
        footerTemplates: List<CompiledFooter> = emptyList(),
        headerTemplates: List<CompiledHeader> = emptyList(),
        captionTemplates: List<CompiledCaptions> = emptyList(),
        /** Company designators, from the list the app already keeps for classifying fields. */
        legalForms: List<String> = emptyList()
    ): Result {
        val sections = ReceiptSections.split(rawText)
        val footerText = sections.footerOrAll(plainText)
        // Read from the letterhead where the document named one, and from the whole text otherwise;
        // it has no arithmetic to prove it either way, so it is read once and kept aside.
        val header = HeaderReader.read(
            headerText = if (sections.marked && sections.header.isNotBlank()) sections.header else plainText,
            templates = headerTemplates,
            captions = captionTemplates,
            legalForms = legalForms
        )

        // The compiled-in parser is the last candidate rather than the first: it is one more opinion
        // about what the totals are, and it is the one that cannot fail loudly, so anything a
        // template proves outranks it. Keeping it means a document that reads correctly today still
        // reads correctly when no template matches it.
        val fallback = ReceiptTotalRegexParser.parse(footerText)
        val candidates = footerTemplates.mapNotNull { FooterReader.read(footerText, it) } +
            // The transaction-slip shape: a Debit/Credit table heading vouches for the one bare
            // figure beneath it, on documents that label no total and sum no rows — structural
            // proof, so it sits with the templates, ahead of the pairings walked from raw text.
            listOfNotNull(BankSlipReader.candidate(plainText)) +
            // Pairings found by walking the whole text rather than its footer, for pages that
            // reached us with no usable structure at all. Offered after the templates, which read a
            // well-formed document more precisely, and before the compiled-in guess.
            CursorScanner.candidates(plainText, footerTemplates) +
            listOf(
                FooterReader.Candidate(
                    templateId = "built-in",
                    grandTotal = fallback.total,
                    invoiceTotal = fallback.invoiceTotal,
                    previousBalance = fallback.previousBalance,
                    net = null,
                    vat = null
                )
            ) +
            // Last of all, the totals the compiled-in parser passed over. A document can print more
            // than one honest total — a bill suggesting what to add for service labels every
            // suggestion "total", each one above what was actually charged — and largest-wins has no
            // way to tell those from the inclusive total it is meant to find. Offered rather than
            // preferred: they are tried only once everything above has failed to reconcile, so the
            // answer that reads a document correctly today is still the first one tried, and a
            // document where nothing closes still reports the same total it always did.
            ReceiptTotalRegexParser.others(footerText).map { runnerUp ->
                FooterReader.Candidate(
                    templateId = "built-in-runner-up",
                    grandTotal = runnerUp,
                    invoiceTotal = null,
                    previousBalance = null,
                    net = null,
                    vat = null
                )
            }

        for (candidate in candidates) {
            val reading = ScanItemsReader.read(
                rawText = rawText,
                totals = candidate.asTotals(),
                templates = itemTemplates,
                quiet = true
            ) ?: continue

            val totals = repaired(candidate.asTotals(), rawText, reading.items)
            Logger.d(
                TAG,
                "Read by footer '${candidate.templateId}' + items '${reading.templateId}': " +
                    "${reading.items.size} row(s); own ${totals.invoiceTotal}, " +
                    "previous ${totals.previousBalance}, due ${totals.total}"
            )
            return Result(totals, reading.items, reading.templateId, candidate.templateId, header)
        }

        // Nothing closed. The totals still have to come from somewhere, so the strongest candidate
        // that read anything serves, and no items are emitted. Its template id travels with the
        // totals — a totals-only reading still owes the caller which shape vouched for the figure
        // (a bank slip's fields are read differently from a receipt's precisely off that id).
        val chosen = candidates.firstOrNull { !it.isEmpty() }
        val totals = chosen?.asTotals() ?: fallback
        Logger.d(
            TAG,
            "No footer+items combination reconciles (${candidates.size} footer candidate(s), " +
                "${itemTemplates.size} item pattern(s)) — totals only, no items"
        )
        return Result(repaired(totals, rawText, null), null, null, chosen?.templateId, header)
    }

    private fun repaired(
        totals: ReceiptTotalRegexParser.Result,
        rawText: String,
        items: List<TableItemsPreParse.Item>?
    ): ReceiptTotalRegexParser.Result {
        val fixed = InvoiceTotalsReconciler.repair(
            totals = InvoiceTotalsReconciler.Totals(
                grandTotal = totals.total,
                invoiceTotal = totals.invoiceTotal,
                previousBalance = totals.previousBalance
            ),
            printed = ScanItemsReader.printedAmounts(rawText),
            itemsSum = items?.sumOf { it.quantity * it.unitPrice }
        )
        return totals.copy(
            total = fixed.grandTotal,
            invoiceTotal = fixed.invoiceTotal,
            previousBalance = fixed.previousBalance
        )
    }
}
