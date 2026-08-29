package com.voxapps.textmatch.extract

/**
 * What a short two-field message yields deterministically before any model sees it — a notification's
 * title and body being the case it was written for.
 * Small models handed a title/text pair pattern-match it onto whichever few-shot example looks
 * closest — a Google Wallet purchase (merchant in the title, card in the text) once came back as
 * an incoming transfer from the card's bank. Every field resolved here is a field the model can
 * no longer invert.
 *
 * The rules are the mutual-exclusion contract the vocabularies exist for, and every one of them
 * declines to the model rather than guess:
 *  - a field carrying a name the caller has been told is a merchant is the vendor, outranking
 *    everything below — nothing is more specific than having been named;
 *  - a field carrying a legal-form token is the vendor — companies carry designators, banks'
 *    notification prose does not; within one field a legal form outranks a bank token;
 *  - a bank token in a non-vendor field names the bank — the matched term, not the whole field,
 *    because "63,00 RON with ING Card ••4535" names ING, not a merchant called all of that;
 *  - when a bank is found and no legal form anywhere, the leftover field is the vendor — but only
 *    when it is short enough to be a name; a sentence is prose, and prose is the model's job;
 *  - the amount is taken only when the text carries exactly one distinct currency-marked figure —
 *    a purchase-plus-balance notification carries two, and choosing between them is not a regex's
 *    call.
 */
object TwoFieldPreParse {

    data class Result(
        val amount: Double?,
        val vendor: String?,
        val bank: String?,
        /** The currency the message states, under the same certainty rule as the amount: exactly
         *  one, or none. A figure is almost never sent without saying what it is a figure of. */
        val currency: String? = null
    )

    /**
     * Which vocabularies mean what, named by the caller.
     *
     * The rules below are about the *relationship* between a legal form and an issuer token, which
     * is the same wherever messages are sent by machines; the names those vocabularies go by belong
     * to whoever curates them.
     */
    data class Roles(
        val legalForm: String,
        val issuer: String,
        /**
         * Names the caller has been told are merchants, if it keeps such a list. Optional because
         * the relationship this object describes — a designator and an issuer token — holds without
         * one; a caller with no list of names passes null and nothing changes.
         */
        val namedVendor: String? = null
    )

    /**
     * A vendor candidate longer than this many tokens is prose, not a name.
     *
     * Three, because a machine-sent sentence collapses once its figure is removed: what is left of
     * "<verb> <figure> <preposition> <counterparty>" is short enough to pass a laxer bound, and the
     * rule then writes a whole sentence into a field that will be shown as a merchant. The names
     * this rule exists to catch are shorter than the sentences it must refuse — an issuer's own
     * label, a company with its legal form, a shop with a branch code — so the tighter bound costs
     * nothing it should have taken and refuses a class it never should have.
     *
     * Refusing is cheap here: a field this declines is simply not resolved deterministically, and
     * naming the counterparty falls to whoever is allowed to guess.
     */
    private const val MAX_VENDOR_TOKENS = 3



    fun parse(
        title: String?,
        text: String?,
        vocabularies: List<VocabularyClassifier.Vocabulary>,
        roles: Roles,
        /** The currencies this app already deals in. A spelling that names one currency ignores
         *  them; one that names several resolves only against these — see [CurrencyCodes.codeOf]. */
        knownCurrencies: Set<String> = emptySet(),
        /**
         * Whether the figure alone may anchor the leftover vendor. Off, a merchant is named only
         * when a bank token, a legal designator or a known name pins it — a bare "Café / 37 RON"
         * yields no vendor, because nothing structural says the title is a shop. On, the field
         * carrying the figure is taken as the transaction detail and the other short field as the
         * merchant, the same shape the bank token anchors. Only a caller that already knows the
         * message is a payment (a starred banking source) should pass true; for anything else the
         * figure is too weak a signal and would name a generic title.
         */
        amountAnchorsVendor: Boolean = false
    ): Result {
        val fields = listOf(title?.trim().orEmpty(), text?.trim().orEmpty())
        val findings = VocabularyClassifier.classifyFields(fields, vocabularies)

        val legalFields = findings.filter { it.vocabulary == roles.legalForm }
            .map { it.lineIndex }.distinct()
        // Legal outranks bank within a field: a vendor field's bank tokens are part of its name.
        val bankFindings = findings.filter {
            it.vocabulary == roles.issuer && it.lineIndex !in legalFields
        }
        val bankFields = bankFindings.map { it.lineIndex }.distinct()

        // Exactly one side per conclusion, everywhere below; anything ambiguous is the model's.
        //
        // A name the caller was told is a merchant settles its field outright. It outranks the
        // designator rule and the issuer rule alike, and for the same reason those rank against each
        // other: the more specific statement wins, and nothing is more specific than having been
        // named. Only one such field, as everywhere here — two is an ambiguity, not a conclusion.
        val namedFields = roles.namedVendor
            ?.let { name -> findings.filter { it.vocabulary == name }.map { it.lineIndex }.distinct() }
            .orEmpty()
        // The listed spelling, not the line it was found in — the same rule the issuer already
        // follows, and for the same reason it gives there. A shop appears as "NAME SRL", "NAME RO-490"
        // and "name" across a month of messages; writing each line verbatim spreads one merchant
        // across a filter list, a set of re-map rules and a recurrence key that no longer agree it is
        // the same shop. The word someone put in the list is what they call it, so that is what the
        // record says.
        //
        // Longest match wins where several terms hit the same line, as [VocabularyClassifier.locate]
        // already resolves it: a shorter entry that is a fragment of a longer one is not a second
        // reading of the line, it is the same reading seen less fully.
        val vendorFromName = namedFields.singleOrNull()?.let { field ->
            findings
                .filter { it.vocabulary == roles.namedVendor && it.lineIndex == field }
                .maxByOrNull { VocabularyClassifier.termKey(it.term).count { c -> c == ' ' } }
                ?.term
        }

        // And the other direction: the listed name is the fuller one and the message says less.
        // Only where the first pass found nothing, only against a line short enough to be a name,
        // and only when exactly one line and one entry agree — the same "exactly one" that governs
        // every other conclusion here.
        val vendorFromFullerName = vendorFromName ?: roles.namedVendor?.let { name ->
            val listed = vocabularies.firstOrNull { it.name == name }?.terms.orEmpty()
            val hits = fields.indices.flatMap { index ->
                val asName = nameOrNull(fields[index]) ?: return@flatMap emptyList()
                listed.filter { VocabularyClassifier.isFullerSpellingOf(it, asName) }
                    .map { index to it }
            }
            hits.map { it.first }.distinct().singleOrNull()?.let { hits.map { h -> h.second }.distinct().singleOrNull() }
        }

        val vendorFromLegal = legalFields.singleOrNull()?.let { fields[it].ifBlank { null } }
        // An issuer token in the field a name already claimed is part of that name.
        val bank = if (bankFields.size == 1 && bankFields.single() !in namedFields) {
            bankFindings.first().term
        } else {
            null
        }

        val vendor = vendorFromFullerName ?: vendorFromLegal ?: run {
            when {
                // The leftover rule: a bank claimed one field and nothing claimed the other — the
                // other is the vendor, when it is name-shaped rather than a sentence.
                namedFields.isEmpty() && legalFields.isEmpty() && bankFields.size == 1 ->
                    nameOrNull(fields[1 - bankFields.single()])
                // The same shape with the figure as the anchor instead of a bank token — only for a
                // caller that already knows this is a payment (see [amountAnchorsVendor]). Requires
                // the figure in exactly one field and no bank token anywhere, so it never overrides
                // the bank-anchored reading above.
                amountAnchorsVendor && namedFields.isEmpty() && legalFields.isEmpty() && bankFields.isEmpty() ->
                    amountFieldIndex(fields)?.let { nameOrNull(fields[1 - it]) }
                else -> null
            }
        }

        return Result(
            amount = singleMarkedAmount(fields),
            vendor = vendor,
            bank = bank,
            // Read from the whole message rather than from beside the figure: a line states its
            // currency once and it is the same currency wherever in the line it stands.
            currency = CurrencyCodes.find(fields.joinToString("\n"), knownCurrencies)
        )
    }

    /**
     * The title for a record whose vendor was resolved deterministically: the vendor, plus the
     * model's category when it offered one, rather than the card the model would otherwise have
     * titled it after. The vendor is what identifies the expense; the category is the one word of
     * context the model still contributes.
     */
    fun composeTitle(vendor: String, category: String?): String =
        listOfNotNull(vendor.trim().ifBlank { null }, category?.trim()?.ifBlank { null })
            .joinToString(" ")

    /**
     * The merchant name inside a field, or null where the field is prose.
     *
     * The figure comes out rather than merely being discounted while counting: a field naming both
     * the shop and the sum is one message, but "<name> <sum>" is not a name, and it is a name that
     * gets shown, matched against a template and offered as a correction. What is left is squeezed
     * back to single spaces — removing a figure from the middle of a field otherwise leaves a gap
     * where it stood.
     */
    private fun nameOrNull(field: String): String? {
        var withoutAmounts = field
        CurrencyMarkedAmounts.find(field).forEach {
            withoutAmounts = withoutAmounts.replace(it.raw, " ")
        }
        val tokens = withoutAmounts.split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (tokens.isEmpty() || tokens.size > MAX_VENDOR_TOKENS) return null
        return tokens.joinToString(" ")
    }

    private fun singleMarkedAmount(fields: List<String>): Double? {
        // The finding of marked figures is core machinery; the certainty policy — exactly one
        // distinct value or nothing — is this parser's own.
        val values = fields.flatMap { f -> CurrencyMarkedAmounts.find(f) }
            .map { it.value }.distinct()
        return values.singleOrNull()?.takeIf { it > 0.0 }
    }

    /**
     * The index of the one field that carries a currency-marked figure, or null when neither does or
     * both do — so the amount anchors a vendor only when it unambiguously belongs to one side.
     */
    private fun amountFieldIndex(fields: List<String>): Int? {
        val withAmount = fields.indices.filter { CurrencyMarkedAmounts.find(fields[it]).isNotEmpty() }
        return withAmount.singleOrNull()
    }
}
