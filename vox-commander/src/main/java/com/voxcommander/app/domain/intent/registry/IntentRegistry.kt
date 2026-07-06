package com.voxcommander.app.domain.intent.registry

/**
 * IntentRegistry: Central source of truth for categories and actions.
 * Optimized based on MacroDroid success logic.
 */
object IntentRegistry {
    private val categories: Map<String, List<String>> = mapOf(
        "audio" to listOf(
            "audio_youtube", 
            "audio_spotify", 
            "media_pause", 
            "media_play", 
            "media_next", 
            "media_prev"
        ),
        "settings" to listOf(
            "vol_up", 
            "vol_down", 
            "wifi_toggle", 
            "bluetooth_toggle"
        ),
        "maps" to listOf(
            "waze_nav", 
            "maps_nav"
        ),
        "home" to listOf(
            "home_toggle", 
            "home_status"
        ),
        "app" to listOf(
            "app_open", 
            "app_close"
        )
    )

    fun getAllCategories(): List<String> = categories.keys.toList()

    fun getActionsForCategory(category: String): List<String> =
        categories[category] ?: emptyList()
}
