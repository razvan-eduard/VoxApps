package com.voxapps.notes.data

/**
 * Pure resolution of the category for a VoxCommander-created note. No Android deps → unit-testable.
 *
 * Order: a spoken category name that matches an existing category (case-insensitive, trimmed) wins;
 * otherwise the user's configured default voice category; otherwise uncategorized. Unknown spoken
 * names do NOT auto-create a category — they fall back to the default (avoids junk from voice typos).
 */
object VoiceCategoryResolver {

    data class Resolved(val categoryId: Long?, val categoryName: String?)

    fun resolve(spokenName: String?, categories: List<Category>, defaultCategoryId: Long?): Resolved {
        val spoken = spokenName?.trim()?.takeIf { it.isNotEmpty() }
        if (spoken != null) {
            val match = categories.firstOrNull { it.name.equals(spoken, ignoreCase = true) }
            if (match != null) return Resolved(match.id, match.name)
        }
        val def = defaultCategoryId?.let { id -> categories.firstOrNull { it.id == id } }
        if (def != null) return Resolved(def.id, def.name)
        return Resolved(null, null)
    }
}
