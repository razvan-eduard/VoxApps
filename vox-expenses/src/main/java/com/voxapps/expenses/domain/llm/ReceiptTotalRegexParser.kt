package com.voxapps.expenses.domain.llm

/**
 * Fast, deterministic regex extraction of a document's total from raw OCR text. Sibling of
 * [DateTimeRegexParser] and used the same way: a value found here is handed to the record directly
 * and the LLM is told not to look for it, which removes both the tokens spent on the field and the
 * chance of a transcription slip on a figure that is printed in plain digits.
 *
 * Only amounts introduced by a total label are considered. That exclusion is what keeps figures
 * that routinely exceed the total out of the running — cash tendered, a card limit, a carried-over
 * balance printed under its own heading — since none of them are labelled as a total. Among the
 * candidates that do qualify, the largest wins: where a document states several totals, the
 * inclusive one is the amount the record is for, and a subtotal or per-period figure is always
 * smaller than the sum that contains it.
 */
object ReceiptTotalRegexParser {

    /**
     * Words that introduce a total, matched case-insensitively as substrings so they also catch
     * the qualified forms documents actually print ("Total de plată", "TOTAL LEI", "Gesamtbetrag").
     * Substring matching also admits "subtotal", which needs no special case: it is by definition
     * smaller than the total containing it, so the largest-wins rule discards it on its own.
     */
    private val totalLabels = listOf(
        "total", "totaal", "totalt", "gesamt", "summe", "suma", "sumă", "importe",
        "montant", "de plata", "de plată", "amount due", "balance due"
    )

    /**
     * A monetary figure with an optional thousands run and an optional fractional part, in either
     * separator convention. Bare integers qualify: not every document prints minor units.
     */
    private val amountRegex = Regex("""\d{1,3}(?:[ .,]\d{3})*(?:[.,]\d{1,2})?|\d+(?:[.,]\d{1,2})?""")

    /** A figure carrying a percent sign is a rate, never the total it is a rate on. */
    private val percentRegex = Regex("""\d+(?:[.,]\d+)?\s*%""")

    data class Result(val total: Double?)

    fun parse(text: String): Result {
        val lines = text.lines()
        var best: Double? = null

        for ((index, line) in lines.withIndex()) {
            val lower = line.lowercase()
            if (totalLabels.none { lower.contains(it) }) continue

            // The value usually shares the label's line, but OCR of a tabular document commonly
            // breaks the column between them, so fall through to the next non-blank line before
            // giving up on this label.
            val candidates = amountsIn(line).ifEmpty {
                lines.drop(index + 1).firstOrNull { it.isNotBlank() }?.let { amountsIn(it) } ?: emptyList()
            }
            for (candidate in candidates) {
                if (best == null || candidate > best) best = candidate
            }
        }

        return Result(best)
    }

    private fun amountsIn(line: String): List<Double> {
        val withoutRates = percentRegex.replace(line, " ")
        return amountRegex.findAll(withoutRates)
            .mapNotNull { normalize(it.value) }
            .filter { it > 0.0 }
            .toList()
    }

    /**
     * Resolves a printed figure to a number without assuming a locale. When both separators appear
     * the rightmost is the decimal one; when only one appears it is a decimal separator solely when
     * it is followed by one or two trailing digits, which is what distinguishes "1.234" the
     * thousands run from "12.34" the amount.
     */
    private fun normalize(raw: String): Double? {
        val cleaned = raw.replace(" ", "")
        val lastDot = cleaned.lastIndexOf('.')
        val lastComma = cleaned.lastIndexOf(',')
        val decimalAt = maxOf(lastDot, lastComma)

        val normalized = when {
            lastDot >= 0 && lastComma >= 0 ->
                cleaned.substring(0, decimalAt).replace(Regex("""[.,]"""), "") +
                    "." + cleaned.substring(decimalAt + 1)
            decimalAt >= 0 && cleaned.length - decimalAt - 1 in 1..2 ->
                cleaned.substring(0, decimalAt) + "." + cleaned.substring(decimalAt + 1)
            else -> cleaned.replace(Regex("""[.,]"""), "")
        }

        return normalized.toDoubleOrNull()?.takeIf { it.isFinite() }
    }
}
