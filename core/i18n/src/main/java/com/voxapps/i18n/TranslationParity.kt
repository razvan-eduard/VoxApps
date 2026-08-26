package com.voxapps.i18n

/**
 * The parity rule every app's translations are held to: all languages carry exactly the same key
 * set. The one sanctioned exception is Romanian's `_many` forms — [LanguageManager.counted] looks
 * them up only for languages that distinguish large numbers, so they exist only where the base key
 * also exists and only in Romanian.
 *
 * Lives in main rather than test sources so each app's own unit test can call it on the JSONs that
 * app actually ships; it has no Android dependency.
 */
object TranslationParity {

    /** Language codes whose `_many` forms are expected rather than a parity break. */
    private const val LARGE_NUMBER_LANGUAGE = "ro"

    /**
     * Returns every parity violation between [reference]'s key set and each other language's, as
     * human-readable lines — empty when the sets agree.
     */
    fun problems(keysByLanguage: Map<String, Set<String>>, reference: String = "en"): List<String> {
        val referenceKeys = keysByLanguage[reference]
            ?: return listOf("reference language '$reference' missing from ${keysByLanguage.keys}")
        val out = mutableListOf<String>()
        for ((language, keys) in keysByLanguage) {
            if (language == reference) continue
            val extra = keys - referenceKeys
            val missing = referenceKeys - keys
            val unsanctioned = extra.filterNot { key ->
                language == LARGE_NUMBER_LANGUAGE &&
                    key.endsWith("_many") &&
                    key.removeSuffix("_many") in keys
            }
            unsanctioned.forEach { out += "$language has '$it' which $reference does not" }
            missing.forEach { out += "$language is missing '$it'" }
        }
        return out
    }
}
