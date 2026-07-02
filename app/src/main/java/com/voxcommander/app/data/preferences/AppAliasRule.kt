package com.voxcommander.app.data.preferences

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
data class AppAliasRule(
    val id: String,
    val packageName: String,
    val displayName: String,
    val aliases: List<String>,
    val enabled: Boolean = true
)
