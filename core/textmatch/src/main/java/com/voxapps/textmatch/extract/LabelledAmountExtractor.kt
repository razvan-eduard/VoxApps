package com.voxapps.textmatch.extract

/**
 * Finds monetary amounts that appear under a label the caller asked about.
 *
 * The labels are an argument rather than a constant here: which words introduce the number a caller
 * wants is domain vocabulary ("total" for a purchase, "amount due" for an invoice, "deposit" for
 * something else), and it changes per language and per country without this code changing at all.
 * Callers that keep such lists in a fetched schema can extend them without a release.
 *
 * Requiring a label is what makes the result trustworthy. Documents are full of numbers that are
 * larger than the one being looked for — cash tendered, a credit limit, a carried-over balance, an
 * account number — and none of them are introduced by the caller's labels, so none of them can be
 * returned. Every qualifying amount is reported; choosing between them is the caller's.
 */
object LabelledAmountExtractor {

    /**
     * A figure with an optional thousands run and an optional fractional part, in either separator
     * convention. Bare integers qualify — not every document prints minor units.
     */
    private val amountRegex = Regex("""\d{1,3}(?:[ .,]\d{3})*(?:[.,]\d{1,2})?|\d+(?:[.,]\d{1,2})?""")

    /** A figure carrying a percent sign is a rate, never an amount. */
    private val percentRegex = Regex("""\d+(?:[.,]\d+)?\s*%""")

    /**
     * [labels] are matched case-insensitively as substrings, so a caller's "total" also admits the
     * qualified forms documents print ("Total de plată", "TOTAL LEI", "Gesamtbetrag").
     *
     * [lookAhead] is how many following lines may be consulted when a label's own line carries no
     * figure. OCR of a tabular document routinely breaks the column between a label and its value,
     * which leaves the two on separate lines; pass 0 to require them to share a line.
     */
    fun find(text: String, labels: Collection<String>, lookAhead: Int = 1): List<AmountFinding> {
        if (labels.isEmpty()) return emptyList()
        val lowered = labels.map { it.lowercase() }.filter { it.isNotBlank() }
        val lines = text.lines()
        val out = mutableListOf<AmountFinding>()

        lines.forEachIndexed { index, line ->
            val lower = line.lowercase()
            // The longest matching label, so a line reading "Total de plata" reports the specific
            // label rather than the generic one it also contains — which is what lets a caller rank
            // candidates by specificity instead of re-reading the document.
            val label = lowered.filter { lower.contains(it) }.maxByOrNull { it.length }
                ?: return@forEachIndexed

            var amounts = amountsIn(line)
            var foundAt = index
            var ahead = 0
            while (amounts.isEmpty() && ahead < lookAhead && foundAt + 1 < lines.size) {
                foundAt++
                ahead++
                if (lines[foundAt].isBlank()) continue
                amounts = amountsIn(lines[foundAt])
            }

            amounts.forEach { (value, raw) ->
                out += AmountFinding(value = value, label = label, raw = raw, lineIndex = index)
            }
        }
        return out
    }

    private fun amountsIn(line: String): List<Pair<Double, String>> {
        val withoutRates = percentRegex.replace(line, " ")
        return amountRegex.findAll(withoutRates)
            .mapNotNull { m -> normalize(m.value)?.let { it to m.value } }
            .filter { it.first > 0.0 }
            .toList()
    }

    /**
     * Resolves a printed figure without assuming a locale. With both separators present the
     * rightmost is the decimal one; with only one present it is decimal only when one or two digits
     * follow it, which is what separates the thousands run "1.234" from the amount "12.34".
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
