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

    data class Result(val amount: Double?, val vendor: String?, val bank: String?)

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
        roles: Roles
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
        val vendorFromName = namedFields.singleOrNull()?.let { fields[it].ifBlank { null } }

        val vendorFromLegal = legalFields.singleOrNull()?.let { fields[it].ifBlank { null } }
        // An issuer token in the field a name already claimed is part of that name.
        val bank = if (bankFields.size == 1 && bankFields.single() !in namedFields) {
            bankFindings.first().term
        } else {
            null
        }

        val vendor = vendorFromName ?: vendorFromLegal ?: run {
            // The leftover rule: a bank claimed one field and nothing claimed the other — the
            // other is the vendor, when it is name-shaped rather than a sentence.
            if (namedFields.isEmpty() && legalFields.isEmpty() && bankFields.size == 1) {
                nameOrNull(fields[1 - bankFields.single()])
            } else null
        }

        return Result(amount = singleMarkedAmount(fields), vendor = vendor, bank = bank)
    }

    /**
     * The title for a record whose vendor was resolved deterministically: the vendor, plus the
     * model's category when it offered one — "LIDL Groceries", not the card the model would
     * otherwise have titled it after. The vendor is what identifies the expense; the category is
     * the one word of context the model still contributes.
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
}
