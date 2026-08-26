package com.voxapps.textmatch.extract

/**
 * What one line of read text is, for the purpose of acting on it.
 *
 * A line on a card or a flyer is usually exactly one thing — an email address, a phone number, a
 * street address, a company name — and the thing it is decides what tapping it should do. This
 * reads that, from shape alone, the same way [AccountIdentifiers] reads an IBAN: a string either
 * has the shape or does not, and nothing is guessed, scored, or asked of a model.
 *
 * One kind per line, decided by precedence. A line carrying two things — an email beside a web
 * address — takes the stronger reading and the rest is reachable through the generic actions,
 * because the tap target is the line and a line cannot launch two things at once.
 *
 * Everything uncertain is [Kind.GENERIC], which is not a failure: generic is the kind whose
 * actions (search, copy) are safe on any text. The specific kinds carry a launch — a dialer, a
 * mail client — and each is admitted only on evidence that has no known mislabel class. A shape
 * that could be an invoice number as easily as a phone number stays generic.
 */
object LineEntities {

    /** What the line is — which is to say, what acting on it means. */
    enum class Kind {
        /** An IBAN or card number, checksum verified — see [AccountIdentifiers]. Copyable, not callable. */
        ACCOUNT,

        /** An email address: something at something dotted. */
        EMAIL,

        /** A web address that says so — a scheme or a leading www. A bare domain does not qualify,
         *  because `terasa.plaja` is as good a Romanian phrase as it is a hostname. */
        URL,

        /** A telephone number, by international prefix, by a telephone label, by parenthesised
         *  grouping, or by exactly matching a national format the caller's country writes. */
        PHONE,

        /** A line that names where something is, by carrying a street word. */
        ADDRESS,

        /** A class of text the person defined themselves — a name, their own pattern, and
         *  whatever app they pointed it at. */
        CUSTOM,

        /** Everything else worth tapping at all. */
        GENERIC
    }

    /**
     * A category of the person's own: [name] is what the chip is called, [pattern] is theirs
     * verbatim. Nothing here is curated or shipped — like the shops vocabulary, it is a list only
     * its owner could write.
     */
    data class CustomCategory(val name: String, val pattern: Regex)

    /**
     * How permissive each reader is allowed to be. [fuzzyKinds] relaxes a kind's evidence the way
     * the duplicate rules' exact/fuzzy switch relaxes a comparison: the same reader, an easier
     * admission, never a different kind of reading. Absent from the set, a kind keeps its strict
     * gates. [custom] is checked before every built-in — a category somebody took the trouble to
     * define outranks anything supplied, the same precedence their own shop names enjoy.
     */
    data class Options(
        val fuzzyKinds: Set<Kind> = emptySet(),
        val custom: List<CustomCategory> = emptyList()
    )

    /**
     * One reading. [value] is the actionable payload, not the line: the address to write to, the
     * number to dial (digits and a possible leading `+`, separators gone), the URL with a scheme a
     * browser accepts. For [Kind.ADDRESS] and [Kind.GENERIC] the payload is the line itself,
     * because the whole line is the query.
     */
    data class Entity(val kind: Kind, val value: String, val customName: String? = null)

    /**
     * Reads [line], or returns null for a line that is nothing at all.
     *
     * [country] is where the reader is — an ISO country or app-language code, case-insensitive —
     * and unlocks that country's national phone formats: `0722111222` written flat is a phone
     * number to anyone in Romania and ten digits to everyone else. No country, no national rung;
     * the international and labelled readings work everywhere.
     */
    fun classify(line: String?, country: String? = null, options: Options = Options()): Entity? {
        val text = line?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        // The person's own categories first — see [Options.custom]. The value is what the pattern
        // matched, not the line: their pattern says which part is the thing.
        options.custom.forEach { category ->
            category.pattern.find(text)?.let { match ->
                return Entity(Kind.CUSTOM, match.value, customName = category.name)
            }
        }

        // Most demanding evidence first. An account line often contains what a phone matcher would
        // happily eat — sixteen digits in groups of four — which is why the checksum-gated reading
        // goes before the shape-gated ones rather than beside them. The checksums are also why
        // ACCOUNT has no fuzzy tier: relaxing a check digit is not an easier match, it is no match.
        AccountIdentifiers.find(text).firstOrNull()?.let { return Entity(Kind.ACCOUNT, it.digits) }
        emailIn(text, fuzzy = Kind.EMAIL in options.fuzzyKinds)?.let { return Entity(Kind.EMAIL, it) }
        urlIn(text, fuzzy = Kind.URL in options.fuzzyKinds)?.let { return Entity(Kind.URL, it) }
        phoneIn(text, country, fuzzy = Kind.PHONE in options.fuzzyKinds)?.let { return Entity(Kind.PHONE, it) }
        if (addressLike(text, fuzzy = Kind.ADDRESS in options.fuzzyKinds)) return Entity(Kind.ADDRESS, text)
        return Entity(Kind.GENERIC, text)
    }

    // --- email ---

    /** Something at something dotted. The TLD wants letters so `user@10.0.0.1` — a login string,
     *  not a correspondent — stays generic. */
    private val EMAIL = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")

    /** OCR misreads a domain's dot as a comma often enough to matter; fuzzy forgives exactly
     *  that, and the value is normalized back to what the address must have been. */
    private val EMAIL_LOOSE = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9\-]+(?:[.,][A-Za-z0-9\-]+)*[.,][A-Za-z]{2,}""")

    private fun emailIn(text: String, fuzzy: Boolean): String? {
        EMAIL.find(text)?.let { return it.value }
        if (!fuzzy) return null
        return EMAIL_LOOSE.find(text)?.value?.let { raw ->
            val at = raw.indexOf('@')
            raw.take(at + 1) + raw.substring(at + 1).replace(',', '.')
        }
    }

    // --- url ---

    /** A scheme, or the www that stands in for one. What follows runs to whitespace; the
     *  punctuation prose hangs on the end of a sentence is trimmed after. */
    private val URL = Regex("""(?:https?://|www\.)[^\s]+""", RegexOption.IGNORE_CASE)

    /** The permissive tier: a bare dotted hostname, no scheme, no www. Exactly the shape the
     *  strict reader refuses — `terasa.plaja` will match it — which is why it only runs for
     *  somebody who switched this kind to fuzzy. */
    private val URL_LOOSE = Regex(
        """\b[a-z0-9][a-z0-9\-]*(?:\.[a-z0-9\-]+)*\.[a-z]{2,6}(?:/\S*)?""",
        RegexOption.IGNORE_CASE
    )

    private fun urlIn(text: String, fuzzy: Boolean): String? {
        val raw = URL.find(text)?.value
            ?: (if (fuzzy) URL_LOOSE.find(text)?.value?.takeIf { '@' !in it } else null)
            ?: return null
        val trimmed = raw.trimEnd('.', ',', ';', ':', ')', ']', '»')
        // A browser accepts www. only with a scheme in front; adding one states nothing the text
        // did not already say.
        return if (trimmed.startsWith("http", ignoreCase = true)) trimmed else "https://$trimmed"
    }

    // --- phone ---

    /**
     * A run that could be written as a telephone number: digits, with the separators and the
     * grouping people put in them, optionally led by `+`.
     */
    private val PHONE_RUN = Regex("""\+?\(?\d[\d\s().\-]*\d""")

    /** A word that announces the number after it. Full words only — the one-letter card
     *  conventions are handled at line start, where they mean something. */
    private val TEL_LABEL = Regex(
        """\b(?:tel|telefon|telephone|phone|mobil|mobile|mob|fax|gsm|whatsapp)\b""",
        RegexOption.IGNORE_CASE
    )

    /** `M: 0744…` / `T: +40…` — the shorthand printed on cards. Only at the start of the line,
     *  where a single letter and a colon have no other reading. */
    private val LINE_START_LABEL = Regex("""^[MTmt]\s*[.:]""")

    /**
     * The national formats that make a flat, unlabelled digit run a phone number.
     *
     * Each entry is a complete format — exact length, exact leading digits — for a country whose
     * numbering plan allows one. A country whose numbers vary in length has no entry, because a
     * partial rule would be the guess this reader refuses; its numbers still read through the
     * prefix, the label, or the parentheses.
     */
    private val NATIONAL_FORMATS: Map<String, Regex> = mapOf(
        "ro" to Regex("""0[237]\d{8}"""),
        "fr" to Regex("""0[67]\d{8}""")
    )

    private fun phoneIn(text: String, country: String?, fuzzy: Boolean): String? {
        // A decimal comma inside the digits is an amount; nothing about a phone number is written
        // with one. Checked on the raw text so `0,72` never contributes its digits to anything.
        val labelled = TEL_LABEL.containsMatchIn(text)
        val shorthand = LINE_START_LABEL.containsMatchIn(text)
        val national = country?.trim()?.lowercase()?.let { NATIONAL_FORMATS[it] }

        for (match in PHONE_RUN.findAll(text)) {
            val run = match.value
            if (touchesLetters(text, match.range)) continue
            if (amountBeside(text, match.range)) continue
            val digits = run.filter { it.isDigit() }
            val plus = run.startsWith("+")
            val international = plus || digits.startsWith("00")
            val grouped = run.contains('(') && run.contains(')')
            when {
                // An international prefix is evidence on its own: nothing else starts with one.
                international && digits.length in INTERNATIONAL_DIGITS ->
                    return if (plus) "+$digits" else digits
                // A label is evidence about the line; the length keeps a labelled code — an order
                // number, a date written flat — from riding on it. The one-letter shorthand is
                // weaker evidence than a word, so it asks for more digits: every real number it
                // introduces has at least nine, and an eight-digit code with none of a phone's
                // shape should not become one because a line opened with a letter.
                labelled && digits.length in LABELLED_DIGITS && !dateShaped(run) ->
                    return digits
                shorthand && digits.length in SHORTHAND_DIGITS && !dateShaped(run) ->
                    return digits
                // Parentheses around a leading group are how area codes are written and how
                // nothing else is.
                grouped && digits.length in LABELLED_DIGITS ->
                    return digits
                // The national rung: the run, written flat, is exactly a number this country
                // writes. Grouping is presentation, so it is stripped before the format is asked.
                national?.matches(digits) == true && !dateShaped(run) ->
                    return digits
                // The permissive tier: a grouped run with no label and nobody's country — the
                // shape alone. Off unless this kind was switched to fuzzy, because a grouped
                // order number is exactly what this would mislabel.
                fuzzy && groupedRun(run) && digits.length in LABELLED_DIGITS && !dateShaped(run) ->
                    return digits
            }
        }
        return null
    }

    /** Seven digits is the shortest closed local number; fifteen is the ITU ceiling. */
    private val INTERNATIONAL_DIGITS = 9..15
    private val LABELLED_DIGITS = 7..15
    private val SHORTHAND_DIGITS = 9..15

    /** Digit groups with separators between them — `0744 123 456`, `21-345-67-89` — as opposed
     *  to one unbroken run, which fuzzy still refuses without a country to vouch for it. */
    private fun groupedRun(run: String): Boolean = run.count { it == ' ' || it == '-' || it == '.' } >= 1

    /** A run whose neighbours are letters is the middle of a code, not a number of its own. */
    private fun touchesLetters(text: String, range: IntRange): Boolean {
        val before = text.getOrNull(range.first - 1)
        val after = text.getOrNull(range.last + 1)
        return before?.isLetter() == true || after?.isLetter() == true
    }

    /** A decimal fraction stuck to the run — `,50` — marks the whole thing a price, whatever
     *  length its digits reach. */
    private fun amountBeside(text: String, range: IntRange): Boolean {
        val after = text.getOrNull(range.last + 1) ?: return false
        return after == ',' && text.getOrNull(range.last + 2)?.isDigit() == true
    }

    /** Two short groups and a longer one, dotted or dashed: a date wearing a phone number's
     *  separators. `12.03.2026` has ten digits' worth of shape and none of a telephone's. */
    private val DATE_SHAPE = Regex("""\d{1,2}[.\-/]\d{1,2}[.\-/]\d{2,4}""")

    private fun dateShaped(run: String): Boolean = DATE_SHAPE.matches(run.trim())

    // --- address ---

    /**
     * The words a street line carries. The same family [the header reader] cuts vendor names at,
     * kept to words that name a way — a bare `Nr.` is not here, because every invoice numbers
     * itself with one.
     */
    private val STREET_WORD = Regex(
        """(?:^|[\s,])(?:str|strada|bd|b-dul|bulevardul|calea|aleea|șos|sos|șoseaua|soseaua|splaiul|intrarea|piața|piata|p-ța|rue|avenue|straße|strasse|platz)(?:[\s.,]|$)""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Whether a line can be the rest of the address begun on the line above it.
     *
     * Printed addresses run over lines — the street on one, the city and postal code on the next —
     * and a per-line reader sees only the first as an address. The caller that knows the order of
     * its lines asks this about the line *after* one it classified as an address; here position is
     * the evidence, and shape only has to not contradict it. A postal code alone qualifies; a
     * place-like line of letters, commas and short digit runs qualifies; anything carrying its own
     * different reading — a label's colon, a hostname's word-internal dot, an address sign, a long
     * digit run — does not. The caller must also require the line to have classified as nothing
     * more specific, so a phone or email line under an address stays what it is.
     */
    fun looksLikeAddressContinuation(line: String?): Boolean {
        val text = line?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        if (text.length > 48) return false
        if (POSTAL_CODE.matches(text)) return true
        if (text.contains('@') || text.contains(':')) return false
        // A dot pressed straight against a letter is a hostname's, not an abbreviation's.
        if (HOSTNAME_DOT.containsMatchIn(text)) return false
        if (LONG_DIGIT_RUN.containsMatchIn(text)) return false
        return PLACE_LINE.matches(text)
    }

    private val POSTAL_CODE = Regex("""\d{4,6}""")
    private val HOSTNAME_DOT = Regex("""\.\p{L}""")
    private val LONG_DIGIT_RUN = Regex("""\d{7,}""")
    private val PLACE_LINE = Regex("""[\p{L}\p{M}\d\s.,\-/()]+""")

    /** The permissive tier: a numbered thing with words around it — `Mihai Eminescu nr. 4` without
     *  the street word the strict reader insists on. Every invoice also numbers itself, which is
     *  why this only runs for somebody who switched addresses to fuzzy. */
    private val NUMBERED_PLACE = Regex("""\bnr\.?\s*\d+""", RegexOption.IGNORE_CASE)

    private fun addressLike(text: String, fuzzy: Boolean): Boolean {
        if (STREET_WORD.containsMatchIn(text)) return true
        if (!fuzzy) return false
        return NUMBERED_PLACE.containsMatchIn(text) && text.count { it == ' ' } >= 2
    }
}
