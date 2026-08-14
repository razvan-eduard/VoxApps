package com.voxapps.textmatch.extract

import com.voxapps.textmatch.FuzzyNameMatcher

/**
 * Word-level correction pairs learned from a human editing a field, and their application to
 * future text.
 *
 * A correction is the observation "this word, wherever it appears, is that word" — a garbled
 * spelling restored to the true one. Its identity is [VocabularyClassifier.termKey] of the garbled
 * word, so the punctuation and casing variants of one garble are one correction, and a key can
 * only ever match a single whitespace-delimited word, never span two real ones. A key derived from
 * a word with internal punctuation is multi-token ("denti t") and therefore matches nothing but
 * the same garble again — reaching other spellings of the same word is the fuzzy tier's job.
 *
 * Everything here is caller-supplied data in, pure text out: no storage, no thresholds, no
 * settings. Learning policy (how many sightings make a correction active, what a conflict means)
 * belongs to the memory that owns the stored corrections.
 *
 * Two tiers with different authority, because they carry different certainty:
 *  - [apply] rewrites only exact key matches — the same garble seen again is the one case with no
 *    failure class, so it may be transcribed;
 *  - [fuzzyCandidates] reports words that merely resemble a known correction. A legitimate word
 *    can resemble a learned fix, so a fuzzy hit is never rewritten here and exists to be offered
 *    to a human as a suggestion.
 */
object FieldCorrections {

    /** [garbageKey] is [VocabularyClassifier.termKey] of the corrected-away word; [fix] is the
     *  replacement verbatim, as the human wrote it. */
    data class Correction(val garbageKey: String, val fix: String)

    /** A word of a field that uniquely resembles one known correction. [word] is the original
     *  spelling as found; [fix] is what the matched correction would put there. */
    data class FuzzyHit(val word: String, val fix: String)

    private val whitespace = Regex("""\s+""")
    private val word = Regex("""\S+""")
    private val letter = Regex("""\p{L}""")

    /** Below this normalized length, resemblance carries no signal — trivially short words sit
     *  within edit-distance of half a dictionary. */
    private const val MIN_FUZZY_WORD_LENGTH = 3

    /**
     * The single correction a manual edit of [old] into [new] teaches, or null when the edit
     * teaches nothing. Declines are the design: each condition removes a class of edits that a
     * word-for-word reading would mislabel.
     *  - Word counts must be equal and exactly one position may differ — anything else is a
     *    rewrite, a reordering, or an insertion, where pairing words is guesswork.
     *  - Both words must contain a letter — numeric edits are value changes, not spelling fixes.
     *  - The words must differ under [VocabularyClassifier.termKey] — a change the key already
     *    treats as identity is styling, and learning it would impose that styling everywhere.
     */
    fun diff(old: String?, new: String?): Correction? {
        val oldWords = old?.trim()?.takeIf { it.isNotEmpty() }?.split(whitespace) ?: return null
        val newWords = new?.trim()?.takeIf { it.isNotEmpty() }?.split(whitespace) ?: return null
        if (oldWords.size != newWords.size) return null
        val differing = oldWords.indices.filter { oldWords[it] != newWords[it] }
        if (differing.size != 1) return null
        val oldWord = oldWords[differing[0]]
        val newWord = newWords[differing[0]]
        if (!letter.containsMatchIn(oldWord) || !letter.containsMatchIn(newWord)) return null
        val key = VocabularyClassifier.termKey(oldWord)
        if (key.isEmpty() || key == VocabularyClassifier.termKey(newWord)) return null
        return Correction(key, newWord)
    }

    /**
     * [field] with every word whose termKey is a key of [corrections] replaced by that
     * correction's fix, whitespace untouched. A single pass over the original words: a fix is
     * never itself re-examined, so corrections cannot chain within one call.
     */
    fun apply(field: String?, corrections: Map<String, String>): String? {
        if (field == null || corrections.isEmpty()) return field
        return word.replace(field) { m ->
            corrections[VocabularyClassifier.termKey(m.value)] ?: m.value
        }
    }

    /** [field] with each hit's word replaced by its fix — building the text a suggestion offers,
     *  never applied without a human accepting it. */
    fun applyHits(field: String?, hits: List<FuzzyHit>): String? {
        if (field == null || hits.isEmpty()) return field
        val byWord = hits.associate { it.word to it.fix }
        return word.replace(field) { m -> byWord[m.value] ?: m.value }
    }

    /**
     * Words of [field] that have no exact key match but resemble exactly one of [corrections] —
     * compared by [FuzzyNameMatcher.namesMatch] against both the correction's fix and its garbage
     * key. A word resembling corrections with two different fixes is ambiguous and reported for
     * neither; a word already identical to a fix under termKey needs nothing and is skipped.
     */
    fun fuzzyCandidates(field: String?, corrections: Map<String, String>): List<FuzzyHit> {
        if (field == null || corrections.isEmpty()) return emptyList()
        val out = mutableListOf<FuzzyHit>()
        val seen = mutableSetOf<String>()
        for (m in word.findAll(field)) {
            val w = m.value
            if (!seen.add(w)) continue
            if (w.length < MIN_FUZZY_WORD_LENGTH) continue
            if (!letter.containsMatchIn(w)) continue
            val wKey = VocabularyClassifier.termKey(w)
            if (corrections.containsKey(wKey)) continue
            val fixes = corrections.entries
                .filter { (key, fix) ->
                    FuzzyNameMatcher.namesMatch(w, fix) || FuzzyNameMatcher.namesMatch(w, key)
                }
                .map { it.value }
                .distinct()
            val fix = fixes.singleOrNull() ?: continue
            if (VocabularyClassifier.termKey(fix) == wKey) continue
            out += FuzzyHit(w, fix)
        }
        return out
    }
}
