package com.voxapps.textmatch.extract

/**
 * The currency a message states, read the same way the amount beside it is.
 *
 * A machine-sent line almost never states a figure without saying what it is a figure of —
 * "315,07 RON", "45.20 EUR", "$12.99" — so the currency is as available as the amount and as
 * certain. It has no business being defaulted from a setting or asked of a model.
 *
 * Codes are ISO 4217 and this table is not a vocabulary anyone curates: three letters per currency,
 * fixed by a standard, plus the symbols and the words people write instead. What a message calls
 * them is the only part that varies, which is why the aliases are here and the codes are not
 * negotiable.
 *
 * Certainty is the same policy the amount follows: exactly one distinct currency in the text, or
 * nothing. Two means a conversion line or a balance in another currency, and choosing between them
 * is not a table's call.
 */
object CurrencyCodes {

    /**
     * What a message writes → the currencies that spelling could mean.
     *
     * One candidate is a fact: "RON" is RON, "€" is EUR, and no reading of them is possible. More
     * than one is the world being ambiguous, and this table refuses to settle it — "$" is six
     * currencies, "lei" is two, "¥" is two, and a reader that picks the popular one is a reader that
     * silently mislabels every record belonging to the other. Those resolve only against currencies
     * the app was already told about, and stay unread otherwise.
     */
    private val aliases: Map<String, List<String>> = mapOf(
        "ron" to listOf("RON"),
        "lei" to listOf("RON", "MDL"),
        "leu" to listOf("RON", "MDL"),
        "eur" to listOf("EUR"), "€" to listOf("EUR"),
        "euro" to listOf("EUR"), "euros" to listOf("EUR"), "euroi" to listOf("EUR"),
        "usd" to listOf("USD"), "us$" to listOf("USD"),
        "$" to listOf("USD", "CAD", "AUD", "NZD", "SGD", "HKD", "MXN"),
        "dollar" to listOf("USD", "CAD", "AUD", "NZD", "SGD", "HKD"),
        "dollars" to listOf("USD", "CAD", "AUD", "NZD", "SGD", "HKD"),
        "dolar" to listOf("USD", "CAD", "AUD"), "dolari" to listOf("USD", "CAD", "AUD"),
        "gbp" to listOf("GBP"), "£" to listOf("GBP"),
        "pound" to listOf("GBP"), "pounds" to listOf("GBP"),
        "lira" to listOf("GBP", "TRY"), "lire" to listOf("GBP", "TRY"),
        "chf" to listOf("CHF"),
        "franc" to listOf("CHF", "XOF", "XPF"), "francs" to listOf("CHF", "XOF", "XPF"),
        "franci" to listOf("CHF", "XOF", "XPF"),
        "huf" to listOf("HUF"), "ft" to listOf("HUF"), "forint" to listOf("HUF"),
        "pln" to listOf("PLN"), "zł" to listOf("PLN"), "zl" to listOf("PLN"),
        "zloty" to listOf("PLN"), "złoty" to listOf("PLN"),
        "czk" to listOf("CZK"), "kč" to listOf("CZK"),
        "koruna" to listOf("CZK", "SEK", "NOK", "DKK", "ISK"),
        "krona" to listOf("SEK", "ISK"), "krone" to listOf("NOK", "DKK"),
        "bgn" to listOf("BGN"), "лв" to listOf("BGN"), "lev" to listOf("BGN"), "leva" to listOf("BGN"),
        "sek" to listOf("SEK"), "nok" to listOf("NOK"), "dkk" to listOf("DKK"), "isk" to listOf("ISK"),
        "mdl" to listOf("MDL"),
        "uah" to listOf("UAH"), "₴" to listOf("UAH"), "hryvnia" to listOf("UAH"),
        "try" to listOf("TRY"), "₺" to listOf("TRY"),
        "cad" to listOf("CAD"), "c$" to listOf("CAD"), "ca$" to listOf("CAD"),
        "aud" to listOf("AUD"), "a$" to listOf("AUD"),
        "jpy" to listOf("JPY"), "yen" to listOf("JPY"),
        "¥" to listOf("JPY", "CNY"),
        "cny" to listOf("CNY"), "rmb" to listOf("CNY"), "yuan" to listOf("CNY"),
        "inr" to listOf("INR"), "₹" to listOf("INR"),
        "rupee" to listOf("INR"), "rupees" to listOf("INR"),
        "rsd" to listOf("RSD"), "dinar" to listOf("RSD", "IQD", "JOD", "KWD", "TND", "DZD")
    )

    /** Longest alias first, so "us$" is never read as the "$" inside it, and "ca$" never as CAD's
     *  own "c$" plus a stray letter. */
    private val byLength: List<Pair<String, List<String>>> =
        aliases.entries.map { it.key to it.value }.sortedByDescending { it.first.length }

    /** Every alias, as one alternation — built once, escaped, longest first. */
    private val anyAlias = Regex(
        byLength.joinToString("|") { Regex.escape(it.first) },
        RegexOption.IGNORE_CASE
    )

    /** Every currency this reader can name, once each. What a screen offering a choice of currency
     *  has to choose from — see [ordered] for the order to offer them in. */
    fun all(): List<String> = aliases.values.flatten().distinct().sorted()

    /**
     * Every currency, in the order a person is likeliest to want one.
     *
     * What the app's language deals in first, then the handful everybody meets, then the rest
     * alphabetically. Not a ranking of currencies — an ordering of a list nobody should have to
     * scroll to find their own.
     */
    fun ordered(languageCode: String?): List<String> {
        val ofLanguage = when (languageCode?.lowercase()?.take(2)) {
            "ro" -> listOf("RON", "MDL")
            "de" -> listOf("EUR", "CHF")
            "fr" -> listOf("EUR", "CHF")
            "en" -> listOf("USD", "GBP", "EUR")
            else -> emptyList()
        }
        val common = listOf("EUR", "USD", "GBP", "CHF", "RON")
        return (ofLanguage + common + all()).distinct()
    }

    /**
     * The code [spelling] stands for, or null when it stands for none — or for several and [known]
     * settles none of them.
     *
     * [known] is what the app has already been told it deals in: its own currency, the currencies
     * its records carry. A spelling with one candidate ignores it entirely; a spelling with several
     * resolves only when exactly one candidate is among them. That is not a preference, it is the
     * ambiguity being settled by something the person already said.
     */
    fun codeOf(spelling: String?, known: Set<String> = emptySet()): String? {
        val key = spelling?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val candidates = byLength.firstOrNull { it.first == key }?.second ?: return null
        candidates.singleOrNull()?.let { return it }
        val upper = known.map { it.uppercase() }.toSet()
        return candidates.filter { it in upper }.singleOrNull()
    }

    /**
     * The one currency [text] states, or null when it states none or more than one.
     *
     * Letter aliases are read on word boundaries so a code is never found inside a word — "leiden"
     * is not lei, "$" is, since a symbol is not a letter and needs no boundary to be one.
     */
    fun find(text: String?, known: Set<String> = emptySet()): String? {
        if (text.isNullOrBlank()) return null
        val spellings = spellingsIn(text)
        if (spellings.isEmpty()) return null
        val codes = spellings.map { codeOf(it, known) }
        // A spelling that names a currency this app cannot pin down leaves the field unread rather
        // than being stepped over: something was stated, and reading past it would report the wrong
        // one or none with equal confidence.
        if (codes.any { it == null }) return null
        return codes.distinct().singleOrNull()
    }

    /** Every currency named in [text], in the order they appear — repeats included, so a caller
     *  applying its own certainty policy can tell one currency stated twice from two currencies. */
    fun codesIn(text: String, known: Set<String> = emptySet()): List<String> =
        spellingsIn(text).mapNotNull { codeOf(it, known) }

    /** The currency spellings [text] carries, unresolved — every one of them, in order. */
    private fun spellingsIn(text: String): List<String> =
        anyAlias.findAll(text).mapNotNull { match ->
            if (standsAlone(text, match.range)) match.value else null
        }.toList()

    /**
     * Whether the match is a word of its own rather than part of one.
     *
     * Only letters can swallow a letter alias; a digit beside a symbol is the ordinary way a price
     * is written, and a digit beside a letter code is how "45EUR" arrives from a machine that never
     * spaces its output.
     */
    private fun standsAlone(text: String, range: IntRange): Boolean {
        if (!text[range.first].isLetter()) return true
        val before = range.first - 1
        val after = range.last + 1
        val leftOk = before < 0 || !text[before].isLetter()
        val rightOk = after >= text.length || !text[after].isLetter()
        return leftOk && rightOk
    }
}
