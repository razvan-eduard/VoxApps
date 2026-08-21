package com.voxapps.textmatch

/**
 * Spellings a given fuzziness level would still accept, generated from the word in front of the
 * user.
 *
 * A level is a number, and a number explains nothing: "2" says neither what it forgives nor what it
 * would start letting through. So the editor shows the level on the user's own trigger word instead
 * of describing it, and this is where those examples come from.
 *
 * Every example sits at the *edge* of the level's budget, which is why this lives beside
 * [FuzzyNameMatcher] rather than beside the screen that displays it. The examples are derived from
 * [FuzzyNameMatcher.editBudget], not from a second copy of it — a demonstration that restates the
 * matcher's arithmetic keeps its promises only until someone changes the budget, and then it
 * promises matches that no longer happen, silently and in the one place a person is being asked to
 * trust it. `FuzzExamplesTest` holds it to that: every example it produces is fed back through the
 * matcher at its own level.
 */
object FuzzExamples {

    /**
     * Lookalike substitutions in the OCR/notification-garble style — the exact mangling fuzzy
     * matching exists to absorb, so demonstration text reads like the real failure it forgives.
     */
    private val GARBLE = mapOf(
        'o' to '0', 'i' to '1', 'l' to '1', 'e' to '3', 'a' to '4',
        's' to '5', 'b' to '8', 't' to '7', 'g' to '9', 'z' to '2'
    )

    /**
     * Examples of [word] that [level] still accepts.
     *
     * Two per level, one per thing the level tolerates. Level 0 shows case variants, since exact
     * matching is already case- and diacritic-insensitive. Level 1 shows two different small typos,
     * one growing from each end, because a typo at the front and a typo at the tail are the two
     * ways a name is usually mistyped. Levels at or above [FuzzyNameMatcher.CONTAINMENT_FROM_LEVEL]
     * show a garble at that level's budget *and* the word embedded in a longer name — containment
     * is the thing those levels newly permit, and it is the one worth seeing before choosing them.
     *
     * [containedTemplate] is a localized format string with a single `%s` for the word.
     */
    fun forLevel(word: String, level: Int, containedTemplate: String): List<String> {
        val trimmed = word.trim()
        if (trimmed.isEmpty()) return emptyList()
        return when {
            level <= 0 -> listOf(trimmed.uppercase(), trimmed.lowercase())
                .filter { it != trimmed }
                .distinct()
                .ifEmpty { listOf(trimmed.uppercase()) }

            level < FuzzyNameMatcher.CONTAINMENT_FROM_LEVEL -> listOf(
                garbled(trimmed, level, fromEnd = false),
                garbled(trimmed, level, fromEnd = true)
            ).distinct()

            else -> listOf(
                garbled(trimmed, level, fromEnd = false),
                containedTemplate.format(trimmed)
            )
        }
    }

    /**
     * [word] with exactly as many characters replaced as [level] forgives — the budget comes from
     * the matcher, so this sits at the boundary rather than near it.
     *
     * Edits are spread evenly rather than clustered: a run of mangled characters in one place reads
     * as a different word, while scattered ones read as the same word typed badly, which is what
     * the level actually tolerates. [fromEnd] mirrors the spread so two examples of the same word
     * differ visibly instead of showing the same damage twice.
     */
    private fun garbled(word: String, level: Int, fromEnd: Boolean): String {
        val edits = FuzzyNameMatcher.editBudget(word.length, level)
        val mutable = word.indices.filter { word[it].isLetterOrDigit() }
        if (mutable.isEmpty()) return word
        val chars = word.toCharArray()
        val step = mutable.size.toDouble() / edits
        (0 until edits)
            .map { i ->
                val slot = minOf(mutable.size - 1, (i * step + step / 2).toInt())
                mutable[if (fromEnd) mutable.size - 1 - slot else slot]
            }
            .toSet()
            .forEach { idx -> chars[idx] = garble(chars[idx]) }
        return String(chars)
    }

    private fun garble(c: Char): Char {
        GARBLE[c.lowercaseChar()]?.let { return it }
        if (c.isDigit()) return if (c == '9') '0' else c + 1
        val shifted = c.lowercaseChar() - 1
        return if (c.isUpperCase()) shifted.uppercaseChar() else shifted
    }
}
