package com.voxcommander.app.domain.voice

import android.content.Context
import com.voxcommander.app.utils.Logger
import org.json.JSONArray
import org.json.JSONObject
import java.util.regex.Pattern

/**
 * Text normalizer that applies a 3-layer Priority-Based Rule Pipeline
 * (Cascade Substitution) loaded from normalization.json in assets.
 *
 * Layer 1: Static exact-match replacements (abbreviations, symbols)
 * Layer 2: Ordered regex rules (interceptors → sweepers with boundary locking)
 * Layer 3: Cleanup regex (whitespace and punctuation artifacts)
 *
 * The normalizer is language-aware: it selects the rule set based on the
 * voice language code (e.g. "en", "ro", "de", "fr").
 */
object TextNormalizer {

    private const val TAG = "TextNormalizer"
    private const val ASSET_FILE = "normalization.json"

    // @Volatile: load() runs on the init thread while normalize() reads the pattern maps
    // on the command thread — this guarantees the published maps are visible once loaded.
    @Volatile private var loaded = false
    private var schemaVersion = 1
    private val languageRules = mutableMapOf<String, LanguageRules>()

    // Pre-compiled regex caches
    private val layer1Patterns = mutableMapOf<String, List<Pair<Pattern, String>>>()
    private val layer2Patterns = mutableMapOf<String, List<Pair<Pattern, String>>>()
    private val layer3Patterns = mutableMapOf<String, List<Pair<Pattern, String>>>()

    private data class LanguageRules(
        val hasLayer1: Boolean,
        val hasLayer2: Boolean,
        val hasLayer3: Boolean
    )

    /**
     * Loads and parses normalization.json from assets.
     * Safe to call multiple times — subsequent calls are no-ops.
     */
    fun load(context: Context) {
        if (loaded) return

        try {
            val json = context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
            val root = JSONObject(json)

            schemaVersion = root.optInt("schema_version", 1)

            val supportedLangs = listOf("en", "ro", "de", "fr")
            for (lang in supportedLangs) {
                if (!root.has(lang)) continue

                val langObj = root.getJSONObject(lang)
                val hasL1 = langObj.has("layer_1_replacements")
                val hasL2 = langObj.has("layer_2_regex")
                val hasL3 = langObj.has("layer_3_cleanup")

                languageRules[lang] = LanguageRules(hasL1, hasL2, hasL3)

                // Parse Layer 1 — static replacements (dict of pattern → replacement)
                if (hasL1) {
                    val rulesObj = langObj.getJSONObject("layer_1_replacements").getJSONObject("rules")
                    val compiled = mutableListOf<Pair<Pattern, String>>()
                    val keys = rulesObj.keys()
                    while (keys.hasNext()) {
                        val patternStr = keys.next()
                        val replacement = rulesObj.getString(patternStr)
                        compiled.add(Pattern.compile(patternStr) to replacement)
                    }
                    layer1Patterns[lang] = compiled
                }

                // Parse Layer 2 — ordered regex rules (array of {pattern, replacement})
                if (hasL2) {
                    val rulesArr = langObj.getJSONObject("layer_2_regex").getJSONArray("rules")
                    val compiled = mutableListOf<Pair<Pattern, String>>()
                    for (i in 0 until rulesArr.length()) {
                        val rule = rulesArr.getJSONObject(i)
                        val patternStr = rule.getString("pattern")
                        val replacement = rule.getString("replacement")
                        compiled.add(Pattern.compile(patternStr) to replacement)
                    }
                    layer2Patterns[lang] = compiled
                }

                // Parse Layer 3 — cleanup (dict of pattern → replacement)
                if (hasL3) {
                    val rulesObj = langObj.getJSONObject("layer_3_cleanup").getJSONObject("rules")
                    val compiled = mutableListOf<Pair<Pattern, String>>()
                    val keys = rulesObj.keys()
                    while (keys.hasNext()) {
                        val patternStr = keys.next()
                        val replacement = rulesObj.getString(patternStr)
                        compiled.add(Pattern.compile(patternStr) to replacement)
                    }
                    layer3Patterns[lang] = compiled
                }
            }

            loaded = true
            Logger.log("Loaded normalization.json (schema v$schemaVersion, languages: ${languageRules.keys})", TAG)
        } catch (e: Exception) {
            Logger.log("Failed to load normalization.json: ${e.message}", TAG)
            loaded = false
        }
    }

    /**
     * Normalizes the given text using the rules for the specified language.
     *
     * @param text The raw text to normalize.
     * @param language The 2-letter language code (e.g. "en", "ro", "de", "fr").
     * @return The normalized text, or the original text if no rules are available.
     */
    fun normalize(text: String, language: String): String {
        if (!loaded) {
            Logger.log("Normalizer not loaded, returning original text", TAG)
            return text
        }

        val langKey = language.substringBefore("_").lowercase()
        if (langKey !in languageRules) {
            // Fallback to English if language not supported
            if ("en" in languageRules) {
                return applyPipeline(text, "en")
            }
            return text
        }

        return applyPipeline(text, langKey)
    }

    private fun applyPipeline(text: String, lang: String): String {
        var result = text

        // Layer 1: Static replacements
        layer1Patterns[lang]?.forEach { (pattern, replacement) ->
            result = pattern.matcher(result).replaceAll(replacement)
        }

        // Layer 2: Ordered regex rules (sequential — each rule mutates state)
        layer2Patterns[lang]?.forEach { (pattern, replacement) ->
            result = pattern.matcher(result).replaceAll(replacement)
        }

        // Layer 3: Cleanup
        layer3Patterns[lang]?.forEach { (pattern, replacement) ->
            result = pattern.matcher(result).replaceAll(replacement)
        }

        return result.trim()
    }

    /**
     * Reloads the normalization rules. Useful for testing or if the asset changes.
     */
    fun reload(context: Context) {
        loaded = false
        languageRules.clear()
        layer1Patterns.clear()
        layer2Patterns.clear()
        layer3Patterns.clear()
        load(context)
    }
}
