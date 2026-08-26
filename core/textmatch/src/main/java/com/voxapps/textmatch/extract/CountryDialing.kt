package com.voxapps.textmatch.extract

/**
 * The country a document belongs to, read from its own web addresses — and what that makes of its
 * phone numbers.
 *
 * A label prints `www.biovita.ro` beside `Tel: 0748 777 222`: the number is national, and the
 * domain says which nation. The ccTLD → dialing-prefix mapping is a published fact about the
 * world, fixed here as a table the same way ISO 4217 is in [CurrencyCodes] — nothing is guessed
 * and nothing is asked of a model, because nothing needs to be. A generic TLD (`.com`, `.org`)
 * names no country and answers nothing.
 *
 * Completing a number matters because the apps a number is handed to — WhatsApp above all — accept
 * only the full international form. The national trunk zero is dropped on the way (the general
 * rule), except for the countries whose plan keeps it: Italy dials +39 06…, and stripping its zero
 * would build a number that rings nowhere.
 */
object CountryDialing {

    /** One country's dialing facts: the prefix, and whether the national trunk `0` survives into
     *  the international form. */
    data class Dial(val prefix: String, val keepsTrunkZero: Boolean = false)

    /** A national number completed: [full] is what an app dials, [prefix] is what was added —
     *  kept apart so a screen can show the correction as the addition it is. */
    data class Completed(val full: String, val prefix: String, val rest: String)

    /** The country-code TLD of a web or email address — the last label of its host. Null for text
     *  with no dotted host in it. */
    fun tldOf(text: String?): String? {
        val trimmed = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val host = trimmed
            .substringAfter('@')
            .removePrefix("https://").removePrefix("http://")
            .substringBefore('/')
            .substringBefore('?')
        val tld = host.substringAfterLast('.', missingDelimiterValue = "")
        return tld.lowercase().takeIf { it.length in 2..3 && it.all { c -> c.isLetter() } }
    }

    /** The dialing facts [tld] names, or null for a TLD that names no country. */
    fun dialOf(tld: String?): Dial? = TLD_DIALING[tld?.trim()?.lowercase()?.removePrefix(".")]

    /**
     * [number] completed to international form under [dial], or null when there is nothing to do:
     * a number already carrying `+` or `00` is already international, and this never rewrites what
     * a document stated outright.
     */
    fun internationalize(number: String?, dial: Dial): Completed? {
        val digits = number?.filter { it.isDigit() || it == '+' }?.takeIf { it.isNotEmpty() } ?: return null
        if (digits.startsWith("+") || digits.startsWith("00")) return null
        val rest = if (!dial.keepsTrunkZero && digits.startsWith("0")) digits.drop(1) else digits
        if (rest.isEmpty()) return null
        return Completed(full = dial.prefix + rest, prefix = dial.prefix, rest = rest)
    }

    /**
     * Every country-code TLD and its dialing prefix. A closed, published mapping — the two lists
     * (ccTLDs, E.164 country codes) are both standards, and joining them is transcription, not
     * judgement. Trunk-zero keepers are marked where the numbering plan says so.
     */
    private val TLD_DIALING: Map<String, Dial> = mapOf(
        // Europe
        "ro" to Dial("+40"), "md" to Dial("+373"),
        "de" to Dial("+49"), "at" to Dial("+43"), "ch" to Dial("+41"),
        "fr" to Dial("+33"), "be" to Dial("+32"), "nl" to Dial("+31"), "lu" to Dial("+352"),
        "it" to Dial("+39", keepsTrunkZero = true),
        "es" to Dial("+34"), "pt" to Dial("+351"), "ad" to Dial("+376"), "mc" to Dial("+377"),
        "gb" to Dial("+44"), "uk" to Dial("+44"), "ie" to Dial("+353"),
        "dk" to Dial("+45"), "se" to Dial("+46"), "no" to Dial("+47"), "fi" to Dial("+358"),
        "is" to Dial("+354"), "fo" to Dial("+298"), "gl" to Dial("+299"),
        "pl" to Dial("+48"), "cz" to Dial("+420"), "sk" to Dial("+421"), "hu" to Dial("+36"),
        "si" to Dial("+386"), "hr" to Dial("+385"), "ba" to Dial("+387"), "rs" to Dial("+381"),
        "me" to Dial("+382"), "mk" to Dial("+389"), "al" to Dial("+355"), "xk" to Dial("+383"),
        "bg" to Dial("+359"), "gr" to Dial("+30"), "cy" to Dial("+357"), "mt" to Dial("+356"),
        "tr" to Dial("+90"),
        "ua" to Dial("+380"), "by" to Dial("+375"), "ru" to Dial("+7"),
        "lt" to Dial("+370"), "lv" to Dial("+371", keepsTrunkZero = true), "ee" to Dial("+372"),
        "ge" to Dial("+995"), "am" to Dial("+374"), "az" to Dial("+994"),
        "sm" to Dial("+378"), "va" to Dial("+379"), "li" to Dial("+423"), "gi" to Dial("+350"),
        // Americas
        "us" to Dial("+1"), "ca" to Dial("+1"), "mx" to Dial("+52"),
        "br" to Dial("+55"), "ar" to Dial("+54"), "cl" to Dial("+56"), "co" to Dial("+57"),
        "pe" to Dial("+51"), "ve" to Dial("+58"), "ec" to Dial("+593"), "bo" to Dial("+591"),
        "py" to Dial("+595"), "uy" to Dial("+598"), "cr" to Dial("+506"), "pa" to Dial("+507"),
        "gt" to Dial("+502"), "sv" to Dial("+503"), "hn" to Dial("+504"), "ni" to Dial("+505"),
        "do" to Dial("+1"), "pr" to Dial("+1"), "jm" to Dial("+1"), "cu" to Dial("+53"),
        // Asia & Oceania
        "il" to Dial("+972"), "sa" to Dial("+966"), "ae" to Dial("+971"), "qa" to Dial("+974"),
        "kw" to Dial("+965"), "bh" to Dial("+973"), "om" to Dial("+968"), "jo" to Dial("+962"),
        "lb" to Dial("+961"), "iq" to Dial("+964"), "ir" to Dial("+98"),
        "in" to Dial("+91"), "pk" to Dial("+92"), "bd" to Dial("+880"), "lk" to Dial("+94"),
        "np" to Dial("+977"), "cn" to Dial("+86"), "hk" to Dial("+852"), "mo" to Dial("+853"),
        "tw" to Dial("+886"), "jp" to Dial("+81"), "kr" to Dial("+82"),
        "th" to Dial("+66"), "vn" to Dial("+84"), "my" to Dial("+60"), "sg" to Dial("+65"),
        "id" to Dial("+62"), "ph" to Dial("+63"), "kh" to Dial("+855"), "la" to Dial("+856"),
        "mm" to Dial("+95"), "kz" to Dial("+7"), "uz" to Dial("+998"), "mn" to Dial("+976"),
        "au" to Dial("+61"), "nz" to Dial("+64"), "fj" to Dial("+679"),
        // Africa
        "eg" to Dial("+20"), "ma" to Dial("+212"), "dz" to Dial("+213"), "tn" to Dial("+216"),
        "ly" to Dial("+218"), "ng" to Dial("+234"), "gh" to Dial("+233"), "ke" to Dial("+254"),
        "tz" to Dial("+255"), "ug" to Dial("+256"), "et" to Dial("+251"), "za" to Dial("+27"),
        "zw" to Dial("+263"), "zm" to Dial("+260"), "ao" to Dial("+244"), "mz" to Dial("+258"),
        "sn" to Dial("+221"), "ci" to Dial("+225"), "cm" to Dial("+237")
    )
}
