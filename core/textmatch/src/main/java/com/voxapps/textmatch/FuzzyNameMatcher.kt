package com.voxapps.textmatch

import java.text.Normalizer

/**
 * Pure resolution of a spoken/free-text name against a candidate list (e.g. a voice-note's spoken
 * category, or a voice-expense's spoken category) — no Android/Room deps, app-agnostic. Shared by
 * `vox-notes` and `vox-expenses` instead of each maintaining its own copy.
 *
 * Order: an exact match (case-insensitive, trimmed) wins; otherwise a fuzzy (Levenshtein) match — this
 * catches diacritic/typo variants ("cumparaturi" vs "Cumpărături") instantly, without invoking any LLM;
 * otherwise [defaultId]; otherwise unresolved. Genuinely distinct names (e.g. "Groceries" vs
 * "Cumpărături") don't fuzzy-match and fall through to the default.
 */
object FuzzyNameMatcher {

    data class Candidate(val id: Long, val name: String)
    data class Resolved(val id: Long?, val name: String?)

    /** A minimum normalized length below which containment alone isn't a reliable signal — two very
     *  short strings can trivially contain one another without meaning the same thing. */
    private const val MIN_CONTAINMENT_LENGTH = 3

    /**
     * Pairwise "do these two free-text names plausibly refer to the same thing" check, reusing the
     * same normalize/Levenshtein machinery [resolve] uses against a candidate list. Order: exact match
     * (after normalization) wins; otherwise one name fully containing the other counts as a match too
     * — a longer descriptive string built around a shorter core name is still the same referent, and
     * plain edit-distance alone would not catch that pairing since the length difference alone can
     * exceed the fuzzy threshold; otherwise a Levenshtein-distance fuzzy match against the same
     * length-relative threshold [resolve] uses.
     */
    fun namesMatch(a: String, b: String): Boolean {
        val normA = normalize(a)
        val normB = normalize(b)
        if (normA.isEmpty() || normB.isEmpty()) return false
        if (normA == normB) return true
        if (normA.length >= MIN_CONTAINMENT_LENGTH && normB.length >= MIN_CONTAINMENT_LENGTH &&
            (normA.contains(normB) || normB.contains(normA))
        ) {
            return true
        }
        return levenshtein(normA, normB) <= threshold(normA.length, normB.length)
    }

    /**
     * [namesMatch] with a caller-chosen strictness instead of the single fixed threshold.
     * Level 0 (or below) is exact normalized equality only; 1 allows small edit distances; 2 is
     * [namesMatch]'s behavior (containment plus the 30% threshold); 3 accepts matches up to 45%
     * of the longer length — progressively easier matches, never a different kind of matching.
     */
    fun namesMatchLeveled(a: String, b: String, level: Int): Boolean {
        val normA = normalize(a)
        val normB = normalize(b)
        if (normA.isEmpty() || normB.isEmpty()) return false
        if (normA == normB) return true
        if (level <= 0) return false
        if (level >= 2 && normA.length >= MIN_CONTAINMENT_LENGTH && normB.length >= MIN_CONTAINMENT_LENGTH &&
            (normA.contains(normB) || normB.contains(normA))
        ) {
            return true
        }
        val ratio = when (level) {
            1 -> 0.15
            2 -> 0.3
            else -> 0.45
        }
        return levenshtein(normA, normB) <= maxOf(1, (maxOf(normA.length, normB.length) * ratio).toInt())
    }

    fun resolve(spokenName: String?, candidates: List<Candidate>, defaultId: Long?): Resolved {
        val spoken = spokenName?.trim()?.takeIf { it.isNotEmpty() }
        if (spoken != null) {
            val exact = candidates.firstOrNull { it.name.equals(spoken, ignoreCase = true) }
            if (exact != null) return Resolved(exact.id, exact.name)

            val fuzzy = bestFuzzyMatch(spoken, candidates)
            if (fuzzy != null) return Resolved(fuzzy.id, fuzzy.name)
        }
        val def = defaultId?.let { id -> candidates.firstOrNull { it.id == id } }
        if (def != null) return Resolved(def.id, def.name)
        return Resolved(null, null)
    }

    private fun bestFuzzyMatch(spoken: String, candidates: List<Candidate>): Candidate? {
        val normSpoken = normalize(spoken)
        return candidates
            .map { it to levenshtein(normSpoken, normalize(it.name)) }
            .filter { (candidate, dist) -> dist <= threshold(normSpoken.length, normalize(candidate.name).length) }
            .minByOrNull { it.second }
            ?.first
    }

    private fun threshold(a: Int, b: Int): Int = maxOf(1, (maxOf(a, b) * 0.3).toInt())

    private fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase().trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "") // strip combining diacritical marks

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) {
                    dp[i - 1][j - 1]
                } else {
                    1 + minOf(dp[i - 1][j - 1], dp[i - 1][j], dp[i][j - 1])
                }
            }
        }
        return dp[a.length][b.length]
    }
}
