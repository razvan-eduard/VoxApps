package com.voxcommander.app.utils

/**
 * Utility class to generate robust Regex patterns from user-selected words.
 * Handles diacritic variations (e.g., "ă" matches "a") and escapes special characters.
 */
object RegexGenerator {

    /**
     * Creates a pattern that matches words in the specified order,
     * allowing any characters between them and ignoring diacritics.
     *
     * Input: ["aprinde", "bucătărie"]
     * Output: "\\baprinde\\b.*?\\bbuc[aăâ][tțţ][aăâ]r[iî]e\\b"
     *
     * NOTE: uses plain ASCII `\b` — do NOT wrap in `(?U:…)`. Android's regex engine
     * rejects the `U` (UNICODE_CHARACTER_CLASS) inline flag with a PatternSyntaxException
     * (desktop JVM accepts it, so unit tests won't catch it). Diacritic insensitivity
     * comes from the char classes below, not from `\b`.
     */
    fun fromWords(selectedWords: List<String>): String {
        if (selectedWords.isEmpty()) return ""
        return buildSequencePattern(selectedWords)
    }

    /**
     * Creates a pattern that matches ANY of the provided word groups (OR logic).
     * Each group uses AND logic internally (words must appear in order within a group).
     * Groups are combined with | (OR).
     *
     * Input: [["aprinde", "bucătărie"], ["lumina", "bucătărie"]]
     * Output: "\\baprinde\\b.*?\\b…\\b|\\blumina\\b.*?\\b…\\b"
     */
    fun fromWordGroups(groups: List<List<String>>): String {
        val validGroups = groups.filter { it.isNotEmpty() }
        if (validGroups.isEmpty()) return ""
        return validGroups.joinToString("|") { buildSequencePattern(it) }
    }

    /** Builds a single ordered "\b word \b.*?\b word \b" sequence. */
    private fun buildSequencePattern(words: List<String>): String =
        words.joinToString(".*?") { word ->
            "\\b" + makeDiacriticInsensitive(word.lowercase()) + "\\b"
        }

    /**
     * Splits a raw sentence into individual words/tokens for UI selection.
     */
    fun splitIntoTokens(sentence: String): List<String> {
        if (sentence.isBlank()) return emptyList()
        
        return sentence
            .replace(Regex("[.,!?;:]"), "") 
            .split(Regex("\\s+"))           
            .filter { it.isNotBlank() }     
    }

    /**
     * Replaces letters with diacritic-aware character classes.
     * Focused primarily on Romanian but extensible.
     */
    private fun makeDiacriticInsensitive(word: String): String {
        val escaped = escapeRegexChars(word)
        val sb = StringBuilder()
        
        for (char in escaped) {
            when (char) {
                'a', 'ă', 'â' -> sb.append("[aăâ]")
                'i', 'î' -> sb.append("[iî]")
                's', 'ș', 'ş' -> sb.append("[sșş]") // Supports both comma and cedilla variants
                't', 'ț', 'ţ' -> sb.append("[tțţ]")
                else -> sb.append(char)
            }
        }
        return sb.toString()
    }

    private fun escapeRegexChars(text: String): String {
        val specials = "\\^$.|?*+()[]{}"
        val sb = StringBuilder()
        for (char in text) {
            if (specials.contains(char)) {
                sb.append("\\")
            }
            sb.append(char)
        }
        return sb.toString()
    }
}
