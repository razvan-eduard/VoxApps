package com.voxapps.textmatch.extract

/**
 * The account or card a piece of text names, read from its shape alone.
 *
 * Nothing here is guessed, matched against a list, or asked of a model, because nothing needs to be:
 * an IBAN, a card number and a masked tail are formats with published rules, and a string either has
 * that shape or does not. That is what separates this from [VocabularyClassifier] — a bank's *name*
 * is a fact about the world that has to be learned, while a bank's *account number* is a fact about
 * the string.
 *
 * It sits beside [TwoFieldPreParse] because it is the same job on the same input: reading one
 * message once, for every field it can be made to yield. That reader's own bank rule is explained
 * with a message carrying a masked tail — the tail is in the text it already walks, and splitting
 * the two readings across modules would mean two passes disagreeing about one sentence. What the
 * app supplies is the records and the lists; what lives here is how a thing is recognised at all.
 *
 * Both checksums are enforced rather than trusted to length. A receipt is full of digit runs — an
 * order number, a till id, a VAT number — and any of them can be sixteen digits long; the check
 * digit is what tells an account from a coincidence, and skipping it would file records against
 * accounts nobody has.
 */
object AccountIdentifiers {

    /** How a text named the account. */
    enum class Kind {
        /** A full IBAN, checksum verified. */
        IBAN,

        /** A full card number, Luhn verified. */
        CARD,

        /** The last digits of a card, the rest masked — what a payment notification usually carries. */
        CARD_TAIL
    }

    /**
     * One reading. [digits] is the identity: the IBAN itself, or a card's trailing digits.
     *
     * A card is stored by its tail rather than in full on purpose. The same card arrives as sixteen
     * digits from a receipt and as two from a notification, and they have to land on one account —
     * so the part they have in common is the part that identifies it. Keeping less is also the safer
     * failure: a tail cannot be spent.
     */
    data class AccountRef(val kind: Kind, val digits: String) {

        /**
         * Whether this and [other] can be the same account.
         *
         * IBANs must match exactly. Cards match when one's digits end with the other's — which is
         * what makes a two-digit tail from a notification meet the full number from a receipt. It is
         * deliberately asymmetric in strength rather than in direction: two tails of different
         * lengths that agree as far as the shorter goes are treated as one card, because the
         * alternative is a second account for every message that showed fewer digits.
         */
        fun sameAs(other: AccountRef): Boolean = when {
            kind == Kind.IBAN || other.kind == Kind.IBAN ->
                kind == other.kind && digits.equals(other.digits, ignoreCase = true)
            digits.length >= other.digits.length -> digits.endsWith(other.digits)
            else -> other.digits.endsWith(digits)
        }
    }

    /**
     * Every account a text names, most specific first.
     *
     * Order matters to a caller taking the first: a message carrying both a full number and a masked
     * tail of the same card should resolve to the number, since it identifies the card more exactly.
     * Duplicates that name the same account collapse.
     */
    fun find(text: String?): List<AccountRef> {
        if (text.isNullOrBlank()) return emptyList()
        val found = mutableListOf<AccountRef>()
        IBAN_PATTERN.findAll(text).forEach { match ->
            val compact = match.value.filterNot { it.isWhitespace() }.uppercase()
            if (ibanChecksumHolds(compact)) found += AccountRef(Kind.IBAN, compact)
        }
        CARD_PATTERN.findAll(text).forEach { match ->
            val digits = match.value.filter { it.isDigit() }
            if (digits.length in CARD_DIGITS && luhnHolds(digits)) {
                found += AccountRef(Kind.CARD, digits.takeLast(CARD_TAIL_LENGTH))
            }
        }
        MASKED_TAIL_PATTERN.findAll(text).forEach { match ->
            val digits = match.groupValues[1]
            if (digits.isNotEmpty()) found += AccountRef(Kind.CARD_TAIL, digits)
        }
        // Most specific first: an IBAN is exact, a full number is exact once its checksum passes, a
        // tail is a fragment. Then drop anything a stronger reading already accounts for.
        return found.sortedBy { it.kind.ordinal }
            .fold(mutableListOf<AccountRef>()) { kept, ref ->
                if (kept.none { it.sameAs(ref) }) kept += ref
                kept
            }
    }

    /** The single account a text names, or null where it names none — or more than one. */
    fun single(text: String?): AccountRef? = find(text).singleOrNull()

    // --- the formats ---

    /**
     * Two letters, two check digits, then the body — either unbroken, or in groups of exactly four,
     * which are the two ways an IBAN is ever written.
     *
     * The two spellings are alternatives rather than one lenient pattern, and that is what stops the
     * match running past the end of the number. A pattern allowing an optional space before a group
     * of one-to-four reads the prose after an IBAN as more of the IBAN — "…7654 32 completed" splits
     * happily into groups — and the checksum then fails on a number that was perfectly good before
     * the reader added a word to it.
     */
    private val IBAN_PATTERN = Regex(
        """\b[A-Z]{2}\d{2}(?:[A-Z0-9]{11,30}|(?: [A-Z0-9]{4})+(?: [A-Z0-9]{1,3})?)\b""",
        RegexOption.IGNORE_CASE
    )

    /** Thirteen to nineteen digits, in groups of four or unbroken, spaced or dashed. */
    private val CARD_PATTERN = Regex("""\b\d{4}(?:[ -]?\d{4}){2,3}[ -]?\d{0,3}\b|\b\d{13,19}\b""")

    private val CARD_DIGITS = 13..19

    /** What a card is stored as — enough to tell two cards apart, not enough to be a card. */
    private const val CARD_TAIL_LENGTH = 4

    /**
     * A run of masking, then the digits that survived it.
     *
     * Dots need two or more; a single one is a decimal separator, and `12.34` would otherwise read
     * as a card ending 34 on every receipt line in the document. Every other masking character is
     * unambiguous enough to count on its own.
     */
    private val MASKED_TAIL_PATTERN = Regex("""(?:[*xX•·#]+|\.{2,})\s?(\d{2,6})\b""")

    // --- the checksums ---

    /** ISO 7064 mod-97: move the first four characters to the end, letters become 10..35, mod 97 is 1. */
    internal fun ibanChecksumHolds(compact: String): Boolean {
        if (compact.length !in IBAN_LENGTH) return false
        if (!compact.take(2).all { it.isLetter() } || !compact.drop(2).take(2).all { it.isDigit() }) return false
        val rearranged = compact.drop(4) + compact.take(4)
        // Folded a digit at a time rather than built into one huge number: an IBAN reaches 34
        // characters, which is past what any integer type here holds.
        var remainder = 0
        for (character in rearranged) {
            val value = when {
                character.isDigit() -> character - '0'
                character.isLetter() -> character.uppercaseChar() - 'A' + 10
                else -> return false
            }
            remainder = if (value > 9) (remainder * 100 + value) % 97 else (remainder * 10 + value) % 97
        }
        return remainder == 1
    }

    private val IBAN_LENGTH = 15..34

    /** The Luhn check every card number carries in its last digit. */
    internal fun luhnHolds(digits: String): Boolean {
        if (digits.isEmpty() || !digits.all { it.isDigit() }) return false
        var sum = 0
        var double = false
        for (index in digits.indices.reversed()) {
            var value = digits[index] - '0'
            if (double) {
                value *= 2
                if (value > 9) value -= 9
            }
            sum += value
            double = !double
        }
        return sum % 10 == 0
    }
}
