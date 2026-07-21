package com.voxapps.commander.domain.intent.interpreter

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.voxapps.commander.domain.intent.model.NluIntent
import com.voxapps.commander.domain.intent.taxonomy.IntentTaxonomy
import com.voxapps.logging.Logger

/**
 * Parses LLM JSON output into NluIntent using the anatomy-based schema.
 *
 * New schema: {action_verb, logical_subject, modifiers, context_words, domain, action, targetApp, category, confidence, extras}
 * Legacy schema: {domain, action, targetApp, parameters, confidence} — mapped to anatomy fields.
 * Old legacy: {category, actionType, artist, track, album, destination} — mapped via IntentTaxonomy.LegacyMapper.
 */
object NluIntentParser {

    private val TAG = "NluIntentParser"
    private val gson = Gson()

    /**
     * Generic cleanup for the LLM hook (satellite raw-prompt requests): strips markdown fences and
     * isolates the first JSON object if the response contains one, otherwise returns the trimmed raw
     * text unchanged. Domain-agnostic — unlike [parse], it does not assume or require JSON output, so
     * it works for future non-JSON hook tasks too. Reuses the exact same cleanup [parse] applies today.
     */
    fun cleanGenericOutput(raw: String): String = extractJsonBlock(raw)

    fun parse(json: String): NluIntent? {
        return try {
            val cleaned = extractJsonBlock(json)
            val obj = JsonParser.parseString(cleaned).asJsonObject
            when {
                obj.has("action_verb") -> parseAnatomySchema(obj)
                obj.has("domain") -> parseLegacyDomainSchema(obj)
                obj.has("category") -> parseOldLegacySchema(obj)
                else -> {
                    Logger.log("Unknown JSON schema — no 'action_verb', 'domain', or 'category' key", TAG)
                    null
                }
            }
        } catch (e: Exception) {
            Logger.log("Failed to parse NluIntent JSON: ${e.message}", TAG)
            null
        }
    }

    /**
     * Extracts the first valid JSON object from an LLM response.
     * Handles markdown fences (```json ... ```) and multiple JSON blocks.
     */
    private fun extractJsonBlock(raw: String): String {
        var text = raw.trim()

        // Strip markdown code fences
        if (text.startsWith("```")) {
            text = text.replace(Regex("^```[a-zA-Z]*\\s*"), "")
            text = text.replace(Regex("```"), "")
        }

        // Find the first { ... } block (handles multiple JSON objects)
        val firstBrace = text.indexOf('{')
        if (firstBrace < 0) return text

        var depth = 0
        var endIdx = -1
        for (i in firstBrace until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        endIdx = i
                        break
                    }
                }
            }
        }

        if (endIdx >= 0) {
            return text.substring(firstBrace, endIdx + 1)
        }

        return text
    }

    private fun JsonObject.getSafeString(key: String): String {
        val el = get(key) ?: return ""
        if (el.isJsonNull) return ""
        return try { el.asString } catch (e: Exception) { "" }
    }

    private fun JsonObject.getSafeStringList(key: String): List<String> {
        val el = get(key) ?: return emptyList()
        if (el.isJsonNull) return emptyList()
        return try {
            val arr = el.asJsonArray
            arr.map { it.asString }
        } catch (e: Exception) {
            // Maybe it's a single string
            try { listOf(el.asString) } catch (_: Exception) { emptyList() }
        }
    }

    private fun JsonObject.getSafeMap(key: String): Map<String, String> {
        val el = get(key) ?: return emptyMap()
        if (el.isJsonNull) return emptyMap()
        return try {
            val type = TypeToken.getParameterized(
                Map::class.java, String::class.java, String::class.java
            ).type
            gson.fromJson(el, type) ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * Parses the new anatomy-based schema:
     * {action_verb, logical_subject, modifiers, context_words, domain, action, targetApp, category, confidence, extras}
     */
    private fun parseAnatomySchema(obj: JsonObject): NluIntent? {
        val actionVerb = obj.getSafeString("action_verb")
        val logicalSubject = obj.getSafeString("logical_subject").ifBlank { null }
        val modifiers = obj.getSafeStringList("modifiers")
        val contextWords = obj.getSafeStringList("context_words")
        val domain = normalizeDomain(obj.getSafeString("domain"))
        val action = normalizeAction(obj.getSafeString("action"))
        val targetApp = if (obj.has("targetApp") && !obj.get("targetApp").isJsonNull) {
            obj.get("targetApp")?.asString
        } else null
        val category = obj.getSafeString("category").ifBlank { null }
        val confidence = if (obj.has("confidence") && !obj.get("confidence").isJsonNull) {
            obj.get("confidence").asFloat
        } else 1.0f
        val extras = obj.getSafeMap("extras")
        val mediaType = obj.getSafeString("media_type").ifBlank { null }

        if (domain.isBlank() && action.isBlank()) {
            Logger.log("LLM returned null domain and action — treating as no intent", TAG)
            return null
        }

        return NluIntent(
            actionVerb = actionVerb,
            logicalSubject = logicalSubject,
            modifiers = modifiers,
            contextWords = contextWords,
            domain = domain,
            action = action,
            targetApp = targetApp,
            category = category,
            confidence = confidence,
            extras = extras,
            mediaType = mediaType
        )
    }

    /**
     * Legacy domain-based schema: {domain, action, targetApp, parameters, confidence}
     * Maps parameters.query → logicalSubject, parameters.category → category, etc.
     */
    private fun parseLegacyDomainSchema(obj: JsonObject): NluIntent? {
        val domain = normalizeDomain(obj.getSafeString("domain"))
        val action = normalizeAction(obj.getSafeString("action"))

        if (domain.isBlank() && action.isBlank()) {
            Logger.log("LLM returned null domain and action — treating as no intent", TAG)
            return null
        }

        val targetApp = if (obj.has("targetApp") && !obj.get("targetApp").isJsonNull) {
            obj.get("targetApp")?.asString
        } else null

        val parameters: Map<String, String> = obj.getSafeMap("parameters")
        val confidence = if (obj.has("confidence") && !obj.get("confidence").isJsonNull) {
            obj.get("confidence").asFloat
        } else 1.0f

        // Map parameters to anatomy fields
        val logicalSubject = parameters["query"]
            ?: parameters["artist"]
            ?: parameters["track"]
            ?: parameters["destination"]
            ?: parameters["contact"]
            ?: parameters["album"]
        val category = parameters["category"]
        val mediaControlType = parameters["mediaControlType"]
        val mediaType = when {
            !parameters["album"].isNullOrBlank() -> "album"
            !parameters["artist"].isNullOrBlank() && parameters["track"].isNullOrBlank() -> "artist"
            else -> null
        }
        val extras = parameters.filterKeys { it != "query" && it != "artist" && it != "track" && it != "album" && it != "destination" && it != "contact" && it != "category" && it != "mediaControlType" }

        return NluIntent(
            actionVerb = action,
            logicalSubject = logicalSubject,
            domain = domain,
            action = action,
            targetApp = targetApp,
            category = category,
            confidence = confidence,
            extras = extras,
            mediaControlType = mediaControlType,
            mediaType = mediaType
        )
    }

    private val domainSynonyms = mapOf(
        "music" to IntentTaxonomy.Domains.AUDIO,
        "media" to IntentTaxonomy.Domains.AUDIO,
        "navigation" to IntentTaxonomy.Domains.MAPS,
        "map" to IntentTaxonomy.Domains.MAPS,
        "message" to IntentTaxonomy.Domains.MESSAGING,
        "chat" to IntentTaxonomy.Domains.MESSAGING,
        "volume" to IntentTaxonomy.Domains.SETTINGS,
        "device" to IntentTaxonomy.Domains.SETTINGS
    )

    private val actionSynonyms = mapOf(
        "search" to IntentTaxonomy.Actions.PLAY,
        "start" to IntentTaxonomy.Actions.PLAY,
        "skip" to IntentTaxonomy.Actions.NEXT,
        "previous" to IntentTaxonomy.Actions.PREV,
        "back" to IntentTaxonomy.Actions.PREV,
        "vol_up" to IntentTaxonomy.Actions.VOLUME_UP,
        "vol_down" to IntentTaxonomy.Actions.VOLUME_DOWN,
        "louder" to IntentTaxonomy.Actions.VOLUME_UP,
        "quieter" to IntentTaxonomy.Actions.VOLUME_DOWN
    )

    private fun normalizeDomain(domain: String): String {
        val lower = domain.lowercase().trim()
        return domainSynonyms[lower] ?: lower
    }

    private fun normalizeAction(action: String): String {
        val lower = action.lowercase().trim()
        return actionSynonyms[lower] ?: lower
    }

    /**
     * Old legacy schema: {category, actionType, artist, track, album, destination}
     * Maps to NluIntent using IntentTaxonomy.LegacyMapper.
     */
    private fun parseOldLegacySchema(obj: JsonObject): NluIntent? {
        val category = obj.get("category")?.asString ?: ""
        val actionType = obj.get("actionType")?.asString ?: ""

        val mapped = IntentTaxonomy.LegacyMapper.fromActionType(actionType)
        val domain = mapped?.domain ?: category
        val action = mapped?.action ?: actionType
        val targetApp = mapped?.targetApp

        val albumVal = obj.get("album")?.takeIf { !it.isJsonNull }?.asString
        val artistVal = obj.get("artist")?.takeIf { !it.isJsonNull }?.asString
        val trackVal = obj.get("track")?.takeIf { !it.isJsonNull }?.asString
        val logicalSubject = artistVal ?: trackVal ?: albumVal
            ?: obj.get("destination")?.takeIf { !it.isJsonNull }?.asString
        val mediaType = when {
            !albumVal.isNullOrBlank() -> "album"
            !artistVal.isNullOrBlank() && trackVal.isNullOrBlank() -> "artist"
            else -> null
        }

        return NluIntent(
            actionVerb = action,
            logicalSubject = logicalSubject,
            domain = domain,
            action = action,
            targetApp = targetApp,
            confidence = 1.0f,
            mediaType = mediaType
        )
    }
}
