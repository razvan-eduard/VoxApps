package com.voxcommander.app.domain.intent.model

/**
 * IntentPayload: Data model for AI-extracted intents.
 * Aligned with optimized MacroDroid logic for media and navigation.
 */
data class IntentPayload(
    val category: String,
    val actionType: String,
    val artist: String? = null,
    val track: String? = null,
    val album: String? = null,
    val destination: String? = null
)
