package com.voxapps.textmatch.extract

/**
 * The one place printed figures become numbers. Both separator conventions resolve without
 * assuming a locale: with both present the rightmost is the decimal one; with one present it is
 * decimal only when one or two digits follow it — what separates the thousands run "1.234" from
 * the amount "12.34". This used to exist twice, character for character, in two modules; a
 * drifted copy of a parsing rule is a wrong amount in a record, so now there is one.
 */
object AmountText {

    /** A figure with an optional thousands run and an optional fractional part, either
     *  convention. Bare integers qualify — not every document prints minor units. */
    val pattern = Regex("""$START\d{1,3}(?:[ .,]\d{3})*(?:[.,]\d{1,2})?|$START\d+(?:[.,]\d{1,2})?""")

    /**
     * A figure a document *printed as an amount*: minor units required, and a thousands run only
     * where one actually follows.
     *
     * The distinction earns its place on scanned text. Recognition runs a stray number into the one
     * beside it — "6688 170.91" — and under [pattern], which treats a space as a thousands separator
     * whether or not one is meant, that reads as six hundred and eighty-eight thousand: a figure the
     * page never printed, which then competes to be somebody's total. Requiring the fractional part
     * and an actual group removes the reading entirely. Callers wanting every number, minor units or
     * not, still want [pattern].
     */
    val printed = Regex("""$START(?:\d{1,3}(?:[ .,]\d{3})+[.,]\d{1,2}|\d+[.,]\d{1,2})""")

    /**
     * A match may not begin part way through a number.
     *
     * Without this, a pattern whose thousands run is optional is free to start at any digit, so
     * "6688" offers "688" as a candidate opening and any grouping rule can build on it. Anchoring
     * every reading to where a number actually starts costs nothing and removes a whole class of
     * invented figure.
     */
    private const val START = "(?<![\\d.,])"

    fun normalize(raw: String): Double? {
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

/**
 * Every figure with a currency marker directly beside it, either side, either spelling. The
 * marker requirement is what separates money from the other numbers a message carries — card
 * fragments, reference codes, counts. Reports all of them; choosing among them is the caller's
 * (a notification parser wanting certainty takes exactly one distinct value and declines on
 * more — see the purchase-plus-balance case).
 */
object CurrencyMarkedAmounts {

    private val marked = Regex(
        """(?:(?:RON|LEI|EUR|USD|GBP|CHF|[\p{Sc}])\s?(${AmountText.pattern.pattern}))""" +
            """|(?:(${AmountText.pattern.pattern})\s?(?:RON|LEI|EUR|USD|GBP|CHF|[\p{Sc}]))""",
        RegexOption.IGNORE_CASE
    )

    fun find(text: String): List<AmountFinding> {
        val out = mutableListOf<AmountFinding>()
        text.lineSequence().forEachIndexed { index, line ->
            marked.findAll(line).forEach { m ->
                val raw = m.groupValues[1].ifEmpty { m.groupValues[2] }
                AmountText.normalize(raw)?.takeIf { it > 0.0 }?.let {
                    out += AmountFinding(value = it, label = "currency", raw = m.value, lineIndex = index)
                }
            }
        }
        return out
    }
}
