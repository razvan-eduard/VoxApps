package com.voxapps.commander.domain.intent.model

import androidx.compose.runtime.Immutable

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
 * @param anyOrder      Match trigger words in any order (lookahead-based), instead of the default
 *                      left-to-right sequence. Mutually exclusive with [lazyQuery]: lazyQuery strips
 *                      the trigger regex's match out of the spoken text via `.replace(...)` to compute
 *                      the leftover query, which only works for a consuming (ordered) pattern — an
 *                      any-order pattern is built from zero-width lookaheads, so `.replace()` on it
 *                      wouldn't remove any characters and would corrupt the extracted query.
 */
@Entity(tableName = "fast_map_rules")
@Immutable
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
    val anyOrder: Boolean = false,
    val sortOrder: Int = 0,
    val isActive: Boolean = true,
    val domain: String = "custom",
    val action: String = "launch",
    val mediaControlType: String = "active_session"
)
