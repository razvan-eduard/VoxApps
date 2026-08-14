package com.voxapps.expenses.data

import android.content.Context
import com.google.gson.annotations.SerializedName
import com.voxapps.services.RemoteSchema
import com.voxapps.textmatch.extract.VocabularyClassifier

/**
 * The vocabularies deterministic field classification runs on — which tokens mean "a company" and
 * which name "a bank". Schema-fed like [ExternalServiceConfig], because this is exactly the kind
 * of data that grows per country and per language without any code changing: a new bank or a new
 * legal form is a repository edit, signed and fetched, not a release.
 *
 * The two lists must stay mutually exclusive after normalization — the classifier's
 * field-assignment rule ("a legal form marks the vendor, a bank name marks the bank") depends on
 * a token never meaning both. [usable] enforces it, so a schema that breaks the invariant is
 * refused the way an empty one would be, and the previous good copy keeps serving.
 */
data class VocabulariesSchema(
    @SerializedName("legal_forms") val legalForms: List<String> = emptyList(),
    @SerializedName("banks") val banks: List<String> = emptyList()
)

object FieldVocabularies {

    private const val TAG = "FieldVocabularies"

    /** The classifier vocabulary names — callers switch on these when reading findings. */
    const val VOCAB_LEGAL_FORM = "legalForm"
    const val VOCAB_BANK = "bank"

    private val schema = RemoteSchema(
        fileName = "field_vocabularies.json",
        type = VocabulariesSchema::class.java,
        usable = { areUsable(it) },
        tag = TAG
    )

    fun init(context: Context) = schema.init(context)

    /**
     * In precedence order: legal forms first, because within one field a legal form outranks a
     * bank token — a merchant can carry a bank's name inside its own name, but a bank's
     * notification text never carries a company designator.
     */
    fun vocabularies(context: Context): List<VocabularyClassifier.Vocabulary> {
        if (!schema.isLoaded) init(context)
        val value = schema.value ?: return emptyList()
        return listOf(
            VocabularyClassifier.Vocabulary(VOCAB_LEGAL_FORM, value.legalForms),
            VocabularyClassifier.Vocabulary(VOCAB_BANK, value.banks)
        )
    }

    /** Both lists present, and mutually exclusive under the classifier's OWN tokenization —
     *  [VocabularyClassifier.termKey], not a re-implementation that could drift from it. */
    fun areUsable(value: VocabulariesSchema): Boolean {
        if (value.legalForms.isEmpty() || value.banks.isEmpty()) return false
        val legal = value.legalForms.map(VocabularyClassifier::termKey).toSet()
        val banks = value.banks.map(VocabularyClassifier::termKey).toSet()
        return legal.intersect(banks).isEmpty()
    }
}
