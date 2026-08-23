package com.voxapps.expenses.data

import android.content.Context
import com.google.gson.annotations.SerializedName
import com.voxapps.expenses.data.preferences.ExpensesSettings
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

    /**
     * Merchants this device names for itself.
     *
     * Supplied by nobody — there is no shipped half and none is possible: merchants are unbounded
     * and personal, so a curated list would never be complete and a stale entry would claim a field
     * it has no business in. As a list one person writes for the shops one person uses, it is
     * bounded by exactly the right thing.
     */
    const val VOCAB_VENDOR = "vendor"

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
     *
     * [settings] carries the two things the file cannot: the terms this device added, and the ones
     * it switched off. See [merge] for how they combine.
     */
    fun vocabularies(
        context: Context,
        settings: ExpensesSettings
    ): List<VocabularyClassifier.Vocabulary> {
        if (!schema.isLoaded) init(context)
        val value = schema.value ?: return emptyList()
        // In precedence order, and the order is the meaning: a merchant this device named outranks a
        // designator rule, which outranks an issuer. Explicit beats general, and the classifier
        // reports in vocabulary order so a caller ranking one above another needs nothing else.
        return listOf(
            VocabularyClassifier.Vocabulary(
                VOCAB_VENDOR,
                merge(emptyList(), settings.customVendors, settings.disabledVendors)
            ),
            VocabularyClassifier.Vocabulary(
                VOCAB_LEGAL_FORM,
                merge(value.legalForms, settings.customLegalForms, settings.disabledLegalForms)
            ),
            VocabularyClassifier.Vocabulary(
                VOCAB_BANK,
                merge(value.banks, settings.customBanks, settings.disabledBanks)
            )
        )
    }

    /** What the file supplies, whichever source is in force — for a screen that lists it. */
    fun provided(context: Context): VocabulariesSchema {
        if (!schema.isLoaded) init(context)
        return schema.value ?: VocabulariesSchema()
    }

    /**
     * The list actually classified against: what the file supplies, less what this device switched
     * off, plus what it added.
     *
     * [disabled] holds normalized keys rather than the words as they were displayed, because the
     * list underneath is replaced wholesale whenever a new one is fetched. Keyed on the word, a
     * term switched off stays off across that replacement, and across a differently-spelled restatement
     * of it — the classifier reads "S.R.L." and "SRL" as one term, so switching off either switches
     * off both. Keyed on position or on the exact string, the next fetch would quietly reinstate it.
     *
     * Suppression reaches a word this device added as well as a supplied one. Keeping a word but
     * not using it is a state worth having — switching a whole list off to see what a capture does
     * without it, and switching it back on — and deleting is the separate, final act. So a term of
     * one's own carries both.
     */
    fun merge(provided: List<String>, custom: Set<String>, disabled: Set<String>): List<String> {
        val seen = mutableSetOf<String>()
        return (provided + custom.sorted())
            .filter { seen.add(VocabularyClassifier.termKey(it)) }
            .filterNot { VocabularyClassifier.termKey(it) in disabled }
    }

    /** Every term in a list, in the order the screen shows them — supplied first, then this
     *  device's own. What a "switch the whole section off" action needs to name. */
    fun keysOf(terms: Collection<String>): Set<String> =
        terms.map(VocabularyClassifier::termKey).toSet()

    /**
     * Whether [term] may join [vocabulary] as a term of this device's own.
     *
     * The classifier's own key decides, so a restatement of something already present is refused
     * however it is spelled. The cross-list check is the load-bearing one: [areUsable] refuses a
     * whole file whose two lists overlap, and a term added here that collides with the other list
     * would put the merged vocabulary in exactly that state — every capture would then quietly stop
     * resolving a vendor, with nothing on screen to say why.
     */
    fun rejectionFor(term: String, vocabulary: String, context: Context, settings: ExpensesSettings): Rejection? {
        val lists = vocabularies(context, settings)
        return rejectionFor(
            term = term,
            own = lists.firstOrNull { it.name == vocabulary }?.terms.orEmpty(),
            other = lists.filterNot { it.name == vocabulary }.flatMap { it.terms }
        )
    }

    /** The judgement itself, apart from where the lists came from — which is the half worth pinning. */
    fun rejectionFor(term: String, own: Collection<String>, other: Collection<String>): Rejection? {
        val key = VocabularyClassifier.termKey(term)
        if (key.isEmpty()) return Rejection.EMPTY
        if (own.any { VocabularyClassifier.termKey(it) == key }) return Rejection.ALREADY_PRESENT
        if (other.any { VocabularyClassifier.termKey(it) == key }) return Rejection.IN_THE_OTHER_LIST
        return null
    }

    /** Why a term was refused, for a screen that has to say so. */
    enum class Rejection { EMPTY, ALREADY_PRESENT, IN_THE_OTHER_LIST }

    /** Both lists present, and mutually exclusive under the classifier's OWN tokenization —
     *  [VocabularyClassifier.termKey], not a re-implementation that could drift from it. */
    fun areUsable(value: VocabulariesSchema): Boolean {
        if (value.legalForms.isEmpty() || value.banks.isEmpty()) return false
        val legal = value.legalForms.map(VocabularyClassifier::termKey).toSet()
        val banks = value.banks.map(VocabularyClassifier::termKey).toSet()
        return legal.intersect(banks).isEmpty()
    }
}
