package com.voxapps.commander.domain.intent.model

import androidx.compose.runtime.Immutable

/**
 * Anatomy-based NLU intent produced by all interpreters (LLM + FastMap).
 *
 * The sentence is dissected into grammatical/logical roles:
 * - [actionVerb]:    The main predicate (e.g. "play", "caută", "spune", "oprește")
 * - [logicalSubject]: The core entity/topic, stripped of verbs/adverbs/prepositions (e.g. "România", "Scorpions")
 * - [modifiers]:     Adverbs/adjectives that modify HOW the action should be done (e.g. "rapid", "încet")
 * - [contextWords]:  Remaining words indicating location, app, or platform (e.g. "pe spotify", "în Ploiești")
 *
 * Routing metadata (domain, action, targetApp, category) is inferred by the LLM
 * or by [NluIntentParser] from the anatomy fields.
 *
 * @param domain          Broad category: "audio", "settings", "maps", "messaging", "system", "home", "search"
 * @param action          Specific action: "play", "pause", "next", "prev", "volume_up", "volume_down",
 *                        "wifi_toggle", "bluetooth_toggle", "navigate", "send", "query"
 * @param targetApp       Explicitly requested app (e.g. "spotify", "youtube", "waze"). null = use default.
 * @param category        Search category: "general", "news", "knowledge", "weather". null for non-search.
 * @param confidence      LLM confidence 0.0–1.0. FastMap rules always 1.0.
 * @param extras          Optional domain-specific key-values (e.g. message_body) that don't fit the anatomy.
 * @param intentAction    Android intent action to fire (used by FastMap rules). null = handler decides.
 * @param uriTemplate     URI template for ACTION_VIEW intents (from FastMap rule or probe).
 * @param mediaControlType How media keys are sent: "active_session" (default), "default_app", "audio_button".
 * @param mediaType        For domain="audio"/action="play" only: "track" (default), "album", or "artist" —
 *                          distinguishes "play the album X"/"play the artist X" from a plain track request.
 *                          null is treated as "track". Not populated by FastMap rules (user-authored rules
 *                          have no notion of this); only the LLM interpreters set it.
 */
@Immutable
data class NluIntent(
    val actionVerb: String,
    val logicalSubject: String? = null,
    val modifiers: List<String> = emptyList(),
    val contextWords: List<String> = emptyList(),
    val domain: String,
    val action: String,
    val targetApp: String? = null,
    val category: String? = null,
    val confidence: Float = 1.0f,
    val extras: Map<String, String> = emptyMap(),
    val intentAction: String? = null,
    val uriTemplate: String? = null,
    val mediaControlType: String? = null,
    val mediaType: String? = null
) {
    /**
     * Serializes the full anatomy (not just [logicalSubject]) for a satellite's extraction pass —
     * see the collapsed voice-command plan. Previously only [logicalSubject] survived the handoff to
     * a satellite's second LLM call, silently discarding [actionVerb]/[modifiers]/[contextWords] this
     * call already computed, forcing the satellite to re-derive them from a flattened string. This is
     * the substitution text for [com.voxapps.ipc.VoxSatelliteSchema.buildPrompt].
     */
    fun toDecompositionText(): String = buildString {
        appendLine("action: $actionVerb")
        logicalSubject?.takeIf { it.isNotBlank() }?.let { appendLine("subject: $it") }
        if (modifiers.isNotEmpty()) appendLine("modifiers: ${modifiers.joinToString(", ")}")
        if (contextWords.isNotEmpty()) appendLine("context: ${contextWords.joinToString(", ")}")
        // Some satellites reuse `category` for an explicitly-named target (e.g. Calendar's "target
        // calendar" hint) rather than a search/routing category — forwarded verbatim like every other
        // anatomy field so the satellite's extraction pass sees it, instead of only surviving on the
        // old fallback path that folded it into a literal string by hand.
        category?.takeIf { it.isNotBlank() }?.let { appendLine("target: $it") }
    }.trim()

    companion object {
        const val EXTRA_MESSAGE_BODY = "message_body"
    }
}
