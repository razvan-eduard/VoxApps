package com.voxcommander.app.domain.intent.taxonomy

/**
 * Single source of truth for intent domains, actions, and their valid parameters.
 * Used by prompt generation, intent validation, and intent handlers.
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

        val ALL = listOf(AUDIO, SETTINGS, MAPS, MESSAGING, SYSTEM, HOME, SEARCH)
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

        val ALL = listOf(PLAY, PAUSE, STOP, NEXT, PREV, VOLUME_UP, VOLUME_DOWN,
            WIFI_TOGGLE, BLUETOOTH_TOGGLE, GPS_TOGGLE, NAVIGATE, SEND, QUERY)
    }

    /**
     * Returns valid actions for a given domain.
     * Custom domains get a generic "launch" action.
     */
    fun getActionsForDomain(domain: String): List<String> = when (domain) {
        Domains.AUDIO -> listOf(Actions.PLAY, Actions.PAUSE, Actions.STOP, Actions.NEXT, Actions.PREV)
        Domains.SETTINGS -> listOf(Actions.VOLUME_UP, Actions.VOLUME_DOWN, Actions.WIFI_TOGGLE, Actions.BLUETOOTH_TOGGLE, Actions.GPS_TOGGLE)
        Domains.MAPS -> listOf(Actions.NAVIGATE)
        Domains.MESSAGING -> listOf(Actions.SEND)
        Domains.SEARCH -> listOf(Actions.QUERY)
        Domains.SYSTEM, Domains.HOME -> listOf("toggle", "status")
        else -> listOf("launch")
    }

    /**
     * Maps legacy actionType values (from old IntentPayload / FastMapRule) to new domain+action pairs.
     * Used for backward compatibility with existing FastMap rules.
     */
    object LegacyMapper {
        data class Mapped(val domain: String, val action: String, val targetApp: String?)

        fun fromActionType(actionType: String): Mapped? = when (actionType) {
            "audio_youtube" -> Mapped(Domains.AUDIO, Actions.PLAY, com.voxcommander.app.utils.PackageNames.YOUTUBE)
            "audio_spotify" -> Mapped(Domains.AUDIO, Actions.PLAY, com.voxcommander.app.utils.PackageNames.SPOTIFY)
            "media_pause" -> Mapped(Domains.AUDIO, Actions.PAUSE, null)
            "media_stop" -> Mapped(Domains.AUDIO, Actions.STOP, null)
            "media_play" -> Mapped(Domains.AUDIO, Actions.PLAY, null)
            "media_next" -> Mapped(Domains.AUDIO, Actions.NEXT, null)
            "media_prev" -> Mapped(Domains.AUDIO, Actions.PREV, null)
            "vol_up" -> Mapped(Domains.SETTINGS, Actions.VOLUME_UP, null)
            "vol_down" -> Mapped(Domains.SETTINGS, Actions.VOLUME_DOWN, null)
            "wifi_toggle" -> Mapped(Domains.SETTINGS, Actions.WIFI_TOGGLE, null)
            "bluetooth_toggle" -> Mapped(Domains.SETTINGS, Actions.BLUETOOTH_TOGGLE, null)
            "waze_nav" -> Mapped(Domains.MAPS, Actions.NAVIGATE, com.voxcommander.app.utils.PackageNames.WAZE)
            "maps_nav" -> Mapped(Domains.MAPS, Actions.NAVIGATE, com.voxcommander.app.utils.PackageNames.GOOGLE_MAPS)
            else -> null
        }
    }
}
