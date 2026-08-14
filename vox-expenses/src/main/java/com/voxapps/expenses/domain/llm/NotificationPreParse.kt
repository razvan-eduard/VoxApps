package com.voxapps.expenses.domain.llm

import com.voxapps.expenses.data.FieldVocabularies
import com.voxapps.textmatch.extract.VocabularyClassifier

/**
 * What a payment notification's two fields yield deterministically before any model sees them.
 * Small models handed a title/text pair pattern-match it onto whichever few-shot example looks
 * closest — a Google Wallet purchase (merchant in the title, card in the text) once came back as
 * an incoming transfer from the card's bank. Every field resolved here is a field the model can
 * no longer invert.
 *
 * The rules are the mutual-exclusion contract the vocabularies exist for, and every one of them
 * declines to the model rather than guess:
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
object NotificationPreParse {

    data class Result(val amount: Double?, val vendor: String?, val bank: String?)

    /** A vendor candidate longer than this many tokens is prose, not a name. */
    private const val MAX_VENDOR_TOKENS = 5



    fun parse(
        title: String?,
        text: String?,
        vocabularies: List<VocabularyClassifier.Vocabulary>
    ): Result {
        val fields = listOf(title?.trim().orEmpty(), text?.trim().orEmpty())
        val findings = VocabularyClassifier.classifyFields(fields, vocabularies)

        val legalFields = findings.filter { it.vocabulary == FieldVocabularies.VOCAB_LEGAL_FORM }
            .map { it.lineIndex }.distinct()
        // Legal outranks bank within a field: a vendor field's bank tokens are part of its name.
        val bankFindings = findings.filter {
            it.vocabulary == FieldVocabularies.VOCAB_BANK && it.lineIndex !in legalFields
        }
        val bankFields = bankFindings.map { it.lineIndex }.distinct()

        // Exactly one side per conclusion; anything ambiguous is the model's.
        val vendorFromLegal = legalFields.singleOrNull()?.let { fields[it].ifBlank { null } }
        val bank = if (bankFields.size == 1) bankFindings.first().term else null

        val vendor = vendorFromLegal ?: run {
            // The leftover rule: a bank claimed one field and nothing claimed the other — the
            // other is the vendor, when it is name-shaped rather than a sentence.
            if (vendorFromLegal == null && legalFields.isEmpty() && bankFields.size == 1) {
                val leftover = fields[1 - bankFields.single()].ifBlank { null }
                leftover?.takeIf { looksLikeName(it) }
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

    private fun looksLikeName(field: String): Boolean {
        var withoutAmounts = field
        com.voxapps.textmatch.extract.CurrencyMarkedAmounts.find(field).forEach {
            withoutAmounts = withoutAmounts.replace(it.raw, " ")
        }
        val tokens = withoutAmounts.split(Regex("""\s+""")).filter { it.isNotBlank() }
        return tokens.isNotEmpty() && tokens.size <= MAX_VENDOR_TOKENS
    }

    private fun singleMarkedAmount(fields: List<String>): Double? {
        // The finding of marked figures is core machinery; the certainty policy — exactly one
        // distinct value or nothing — is this parser's own.
        val values = fields.flatMap { f -> com.voxapps.textmatch.extract.CurrencyMarkedAmounts.find(f) }
            .map { it.value }.distinct()
        return values.singleOrNull()?.takeIf { it > 0.0 }
    }
}
