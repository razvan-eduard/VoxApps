package com.voxapps.commander.data.preferences

import androidx.compose.runtime.Immutable

/**
 * Represents a user-defined alias rule for an app.
 * When the LLM returns a targetApp matching one of [aliases], it resolves to [packageName].
 *
 * @param id          Unique identifier (UUID string).
 * @param packageName Target app package name (e.g. "com.github.libretube").
 * @param displayName Human-readable app name at creation time (for UI display).
 * @param aliases     List of alias names that should resolve to this app (e.g. "youtube", "yt").
 * @param enabled     Whether this rule is active.
 */
@Immutable
data class AppAliasRule(
    // Every parameter carries a default so Gson builds these through the Kotlin constructor. One
    // without a default and Kotlin generates no no-arg constructor, Gson allocates the object
    // without running any constructor at all, and every absent field arrives null — including
    // `aliases`, which the matcher then iterates. These come back from an exported backup that may
    // have been edited or written by an older build.
    val id: String = "",
    val packageName: String = "",
    val displayName: String = "",
    val aliases: List<String> = emptyList(),
    val enabled: Boolean = true
)
