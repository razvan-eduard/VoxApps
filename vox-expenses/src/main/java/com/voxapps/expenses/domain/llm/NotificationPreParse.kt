package com.voxapps.expenses.domain.llm

import com.voxapps.expenses.data.FieldVocabularies
import com.voxapps.textmatch.extract.TwoFieldPreParse
import com.voxapps.textmatch.extract.VocabularyClassifier

/**
 * What a payment notification's title and text yield before any model sees them.
 *
 * The reading itself is [TwoFieldPreParse]: which field is a vendor, which token names the issuer,
 * when an amount may be taken at all. Those rules are about the shape of a machine-sent message and
 * hold wherever one arrives.
 *
 * What is this app's is which curated lists play those parts — and the pairing has to be stated
 * somewhere, since a vocabulary called "legal form" is only a legal form because this app says so.
 * That statement is this file, and it is the whole of it.
 */
object NotificationPreParse {

    /** The vendor/bank pair as this app names them. */
    private val ROLES = TwoFieldPreParse.Roles(
        legalForm = FieldVocabularies.VOCAB_LEGAL_FORM,
        issuer = FieldVocabularies.VOCAB_BANK
    )

    /** [TwoFieldPreParse.Result]'s issuer is a bank here. */
    typealias Result = TwoFieldPreParse.Result

    fun parse(
        title: String?,
        text: String?,
        vocabularies: List<VocabularyClassifier.Vocabulary>
    ): TwoFieldPreParse.Result = TwoFieldPreParse.parse(title, text, vocabularies, ROLES)

    /**
     * `Vendor Category`, or whichever half is known. Composed rather than modelled: with the
     * vendor suppressed from the prompt, a model-invented title names whatever text remained.
     */
    fun composeTitle(vendor: String, category: String?): String =
        listOfNotNull(vendor.trim().ifBlank { null }, category?.trim()?.ifBlank { null })
            .joinToString(" ")
}
