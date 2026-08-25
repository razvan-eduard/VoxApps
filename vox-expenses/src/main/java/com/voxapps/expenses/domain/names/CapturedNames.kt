package com.voxapps.expenses.domain.names

import com.voxapps.expenses.data.FieldVocabularies
import com.voxapps.textmatch.extract.VocabularyClassifier
import com.voxapps.textmatch.extract.VocabularyFinding

/**
 * The names a capture's own text carries, read the same way whatever brought the text.
 *
 * A notification and a scanned page are different shapes — two short fields against a wall of
 * printed lines — and each has its own reading for what only it can say: a notification has no
 * header, a page has no title. But *the names* are the same question asked of the same lists, and
 * asking it twice in two places is how the two ended up disagreeing: a page naming ING gave no bank
 * at all unless a model was asked, while a message naming ING gave one with nothing asked of
 * anybody.
 *
 * The certainty rule is the one every reading in this app follows: exactly one, or nothing. Text
 * naming two banks has named neither, and picking the first would be picking by position.
 */
object CapturedNames {

    data class Read(val bank: String?, val vendor: String?)

    fun of(text: String?, vocabularies: List<VocabularyClassifier.Vocabulary>): Read {
        if (text.isNullOrBlank()) return Read(null, null)
        val findings = VocabularyClassifier.classify(text, vocabularies)
        return Read(
            bank = soleTerm(findings, FieldVocabularies.VOCAB_BANK),
            vendor = soleTerm(findings, FieldVocabularies.VOCAB_VENDOR)
        )
    }

    /** The listed spelling, not the line it was found in — one merchant written three ways across a
     *  month of documents is still one merchant, and the word somebody put in the list is what they
     *  call it. */
    private fun soleTerm(
        findings: List<VocabularyFinding>,
        vocabulary: String
    ): String? = findings
        .filter { it.vocabulary == vocabulary }
        .map { it.term }
        .distinctBy { it.lowercase() }
        .singleOrNull()
}
