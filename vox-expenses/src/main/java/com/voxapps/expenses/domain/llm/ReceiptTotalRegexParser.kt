package com.voxapps.expenses.domain.llm

import com.voxapps.textmatch.extract.LabelledAmountExtractor

/**
 * Expenses' reading of a document's total. The finding of candidates belongs to
 * [LabelledAmountExtractor], which reports every amount printed under one of the labels below and
 * rules on none of them; what remains here is the part that is an expense decision rather than a
 * fact about text.
 *
 * That decision is to take the largest candidate. Where a document states several totals, the
 * inclusive one is the amount the record is for — a subtotal, a per-period charge or a tax line is
 * always smaller than the sum containing it. Labels are required, which is what keeps figures that
 * routinely exceed the total out of the running: cash tendered, a credit limit and a carried-over
 * balance are printed under headings of their own, so they are never candidates.
 */
object ReceiptTotalRegexParser {

    /**
     * Words that introduce a total, matched case-insensitively as substrings so the qualified forms
     * documents print are covered too ("Total de plată", "TOTAL LEI", "Gesamtbetrag"). Substring
     * matching also admits "subtotal", which needs no special case: it is by definition smaller than
     * the total containing it, so the largest-wins rule discards it.
     */
    private val totalLabels = listOf(
        "total", "totaal", "totalt", "gesamt", "summe", "suma", "sumă", "importe",
        "montant", "de plata", "de plată", "amount due", "balance due"
    )

    /** Labels naming an INVOICE's own total — when present it beats the largest-wins rule, since
     *  the largest figure on an invoice routinely includes a carried-over balance. */
    private val invoiceTotalLabels = listOf(
        "total factura", "total factură", "invoice total", "total invoice",
        "rechnungsbetrag", "total facture"
    )

    /** Labels naming a balance carried from before this document. */
    private val previousBalanceLabels = listOf(
        "sold anterior", "sold precedent", "previous balance", "saldo anterior",
        "restanta", "restanță", "restante", "restanțe", "overdue"
    )

    data class Result(
        /** The record's headline amount: the LARGEST labelled total — the grand total, what
         *  actually gets paid. The long-standing rule; an invoice's smaller figures are extras. */
        val total: Double?,
        /** The invoice's OWN charges when labelled — smaller than [total] whenever a balance is
         *  carried; the deterministic line-items gate validates against THIS, since items sum to
         *  the invoice's own charges, never to someone's unpaid history. */
        val invoiceTotal: Double? = null,
        val previousBalance: Double? = null
    )

    fun parse(text: String): Result {
        val largest = LabelledAmountExtractor.find(text, totalLabels).maxOfOrNull { it.value }
        val invoiceTotal = LabelledAmountExtractor.find(text, invoiceTotalLabels).maxOfOrNull { it.value }
            ?.takeIf { largest == null || it < largest }
        val previousBalance = LabelledAmountExtractor.find(text, previousBalanceLabels).maxOfOrNull { it.value }
        return Result(
            total = largest ?: invoiceTotal,
            invoiceTotal = invoiceTotal,
            previousBalance = previousBalance
        )
    }
}
