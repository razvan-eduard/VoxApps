package com.voxcommander.app.domain.intent.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * FastMapRule: L1 trigger rule that bypasses LLM intent processing.
 * User selects words from voice input to build trigger + query, then picks a target app + intent action.
 *
 * @param allWords      All tokens from the voice input (for re-editing).
 * @param triggerWords  Subset of words selected for trigger matching (can be empty if query is set).
 * @param triggerGroups Additional trigger word groups, each group is an alternative (OR). Each group uses AND logic internally.
 * @param queryWords    Subset of words selected as query argument for the intent (can be empty if trigger is set).
 * @param targetPackage Target app package name.
 * @param intentAction  Android intent action to fire (e.g. MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).
 * @param uriTemplate   URI template for ACTION_VIEW intents (e.g. "waze://?q={destination}&navigate=yes"). null = no deep link.
 * @param domain        Intent domain: "custom" (app launch), "settings", "audio", "maps", "messaging", etc.
 * @param action        Intent action: "launch", "volume_up", "volume_down", "wifi_toggle", "play", "navigate", etc.
 * @param mediaControlType  For audio transport controls: "active_session" (default), "default_app", "audio_button".
 */
@Entity(tableName = "fast_map_rules")
data class FastMapRule(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val allWords: List<String> = emptyList(),
    val triggerWords: List<String> = emptyList(),
    val triggerGroups: List<List<String>> = emptyList(),
    val queryWords: List<String> = emptyList(),
    val targetPackage: String = "",
    val intentAction: String = "",
    val uriTemplate: String? = null,
    val lazyQuery: Boolean = false,
    val sortOrder: Int = 0,
    val isActive: Boolean = true,
    val domain: String = "custom",
    val action: String = "launch",
    val mediaControlType: String = "active_session"
)
