package com.voxapps.commander.domain.intent.taxonomy

import com.voxapps.commander.domain.intent.registry.IntentCatalog

/**
 * Single source of truth for intent domains, actions, and their valid parameters.
 * Used by prompt generation, intent validation, and intent handlers.
 *
 * The domain/action string CONSTANTS are code contracts (handlers dispatch on them) and stay here.
 * The LISTS (`Domains.ALL`, `Actions.ALL`, `getActionsForDomain`) are the NLU *vocabulary* — they
 * read from `intents.json` via [IntentCatalog] (hot-reloadable), falling back to the code seed
 * below when the JSON isn't loaded. This lets a new vertical enter the LLM's vocabulary + Rules UI
 * by config; a domain with bespoke nucleus behavior still needs a handler.
 */
object IntentTaxonomy {

    object Domains {
        const val AUDIO = "audio"
        const val SETTINGS = "settings"
        const val MAPS = "maps"
        const val MESSAGING = "messaging"
        const val SYSTEM = "system"
        const val HOME = "home"
        const val SEARCH = "search"

        /** JSON-backed (intents.json `taxonomy.domains`); IntentCatalog owns the seed fallback. */
        val ALL: List<String> get() = IntentCatalog.taxonomyDomains()
    }

    object Actions {
        // Audio
        const val PLAY = "play"
        const val PAUSE = "pause"
        const val STOP = "stop"
        const val NEXT = "next"
        const val PREV = "prev"

        // Settings
        const val VOLUME_UP = "volume_up"
        const val VOLUME_DOWN = "volume_down"
        const val WIFI_TOGGLE = "wifi_toggle"
        const val BLUETOOTH_TOGGLE = "bluetooth_toggle"
        const val GPS_TOGGLE = "gps_toggle"

        // Maps
        const val NAVIGATE = "navigate"

        // Messaging
        const val SEND = "send"

        // Search
        const val QUERY = "query"

        /** JSON-backed (intents.json `taxonomy.actions`); IntentCatalog owns the seed fallback. */
        val ALL: List<String> get() = IntentCatalog.taxonomyActions()
    }

    /**
     * Valid actions for a domain, sourced from intents.json `taxonomy.actions_by_domain`
     * (IntentCatalog owns the seed fallback). Custom/unknown domains get a generic "launch" action.
     */
    fun getActionsForDomain(domain: String): List<String> =
        IntentCatalog.taxonomyActionsForDomain(domain)?.takeIf { it.isNotEmpty() } ?: listOf("launch")

    /**
     * Maps legacy actionType values (from old IntentPayload / FastMapRule) to new domain+action pairs.
     * Used for backward compatibility with existing FastMap rules.
     */
    object LegacyMapper {
        data class Mapped(val domain: String, val action: String, val targetApp: String?)

        fun fromActionType(actionType: String): Mapped? = when (actionType) {
            "audio_youtube" -> Mapped(Domains.AUDIO, Actions.PLAY, com.voxapps.commander.utils.PackageNames.YOUTUBE)
            "audio_spotify" -> Mapped(Domains.AUDIO, Actions.PLAY, com.voxapps.commander.utils.PackageNames.SPOTIFY)
            "media_pause" -> Mapped(Domains.AUDIO, Actions.PAUSE, null)
            "media_stop" -> Mapped(Domains.AUDIO, Actions.STOP, null)
            "media_play" -> Mapped(Domains.AUDIO, Actions.PLAY, null)
            "media_next" -> Mapped(Domains.AUDIO, Actions.NEXT, null)
            "media_prev" -> Mapped(Domains.AUDIO, Actions.PREV, null)
            "vol_up" -> Mapped(Domains.SETTINGS, Actions.VOLUME_UP, null)
            "vol_down" -> Mapped(Domains.SETTINGS, Actions.VOLUME_DOWN, null)
            "wifi_toggle" -> Mapped(Domains.SETTINGS, Actions.WIFI_TOGGLE, null)
            "bluetooth_toggle" -> Mapped(Domains.SETTINGS, Actions.BLUETOOTH_TOGGLE, null)
            "waze_nav" -> Mapped(Domains.MAPS, Actions.NAVIGATE, com.voxapps.commander.utils.PackageNames.WAZE)
            "maps_nav" -> Mapped(Domains.MAPS, Actions.NAVIGATE, com.voxapps.commander.utils.PackageNames.GOOGLE_MAPS)
            else -> null
        }
    }
}
