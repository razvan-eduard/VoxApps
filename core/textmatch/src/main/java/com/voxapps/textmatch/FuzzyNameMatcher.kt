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
