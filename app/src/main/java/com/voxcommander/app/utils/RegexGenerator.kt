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
     * Output: "(?U:\\baprinde\\b.*?\\bbuc[aăâ][tțţ][aăâ]r[iî]e\\b)"
     *
     * The result is wrapped in a Unicode-aware group (see [wrapUnicode]) so that
     * `\b` works for words starting/ending in a diacritic.
     */
    fun fromWords(selectedWords: List<String>): String {
        if (selectedWords.isEmpty()) return ""
        return wrapUnicode(buildSequencePattern(selectedWords))
    }

    /**
     * Creates a pattern that matches ANY of the provided word groups (OR logic).
     * Each group uses AND logic internally (words must appear in order within a group).
     * Groups are combined with | (OR), and the whole alternation shares one
     * Unicode-aware boundary group.
     *
     * Input: [["aprinde", "bucătărie"], ["lumina", "bucătărie"]]
     * Output: "(?U:\\baprinde\\b.*?\\b…\\b|\\blumina\\b.*?\\b…\\b)"
     */
    fun fromWordGroups(groups: List<List<String>>): String {
        val validGroups = groups.filter { it.isNotEmpty() }
        if (validGroups.isEmpty()) return ""
        return wrapUnicode(validGroups.joinToString("|") { buildSequencePattern(it) })
    }

    /** Builds a single ordered "\b word \b.*?\b word \b" sequence (no boundary wrapper). */
    private fun buildSequencePattern(words: List<String>): String =
        words.joinToString(".*?") { word ->
            "\\b" + makeDiacriticInsensitive(word.lowercase()) + "\\b"
        }

    /**
     * Wraps [body] in a Unicode-aware group so `\b` treats diacritics (ă â î ș ț
     * and their variants) as word characters. Without `(?U:…)`, `\b` uses ASCII
     * `\w`, so a word starting or ending in a diacritic (e.g. "masă", "ăsta") has
     * a broken word boundary and fails to match.
     */
    private fun wrapUnicode(body: String): String = "(?U:$body)"

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
