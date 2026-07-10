package com.voxapps.notes.data

import java.text.Normalizer

/**
 * Pure resolution of the category for a VoxCommander-created note. No Android deps → unit-testable.
 *
 * Order: a spoken category name that matches an existing category (case-insensitive, trimmed) wins;
 * otherwise a fuzzy (Levenshtein) match against existing categories — this catches diacritic/typo
 * variants ("cumparaturi" vs "Cumpărături") instantly, without ever invoking the LLM auto-merge hook;
 * otherwise the user's configured default voice category; otherwise uncategorized. Genuinely distinct
 * names (e.g. "Groceries" vs "Cumpărături") don't fuzzy-match and fall through to the default — if
 * `autoCreateVoiceCategory` is on, those get created as new categories, to be reconciled later by the
 * Auto-Merge Categories feature.
 */
object VoiceCategoryResolver {

    data class Resolved(val categoryId: Long?, val categoryName: String?)

    fun resolve(spokenName: String?, categories: List<Category>, defaultCategoryId: Long?): Resolved {
        val spoken = spokenName?.trim()?.takeIf { it.isNotEmpty() }
        if (spoken != null) {
            val exact = categories.firstOrNull { it.name.equals(spoken, ignoreCase = true) }
            if (exact != null) return Resolved(exact.id, exact.name)

            val fuzzy = bestFuzzyMatch(spoken, categories)
            if (fuzzy != null) return Resolved(fuzzy.id, fuzzy.name)
        }
        val def = defaultCategoryId?.let { id -> categories.firstOrNull { it.id == id } }
        if (def != null) return Resolved(def.id, def.name)
        return Resolved(null, null)
    }

    private fun bestFuzzyMatch(spoken: String, categories: List<Category>): Category? {
        val normSpoken = normalize(spoken)
        return categories
            .map { it to levenshtein(normSpoken, normalize(it.name)) }
            .filter { (cat, dist) -> dist <= threshold(normSpoken.length, normalize(cat.name).length) }
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
