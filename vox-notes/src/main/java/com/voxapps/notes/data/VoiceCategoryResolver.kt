package com.voxapps.notes.data

import com.voxapps.textmatch.FuzzyNameMatcher

/**
 * Notes-specific façade over the shared [FuzzyNameMatcher] (see `:core:textmatch`) — keeps
 * [Category]-typed call sites in `NotesRepository` unchanged while the actual exact/fuzzy matching
 * logic lives in one place, shared with `vox-expenses`'s voice-category resolution.
 */
object VoiceCategoryResolver {

    data class Resolved(val categoryId: Long?, val categoryName: String?)

    fun resolve(spokenName: String?, categories: List<Category>, defaultCategoryId: Long?): Resolved {
        val result = FuzzyNameMatcher.resolve(
            spokenName = spokenName,
            candidates = categories.map { FuzzyNameMatcher.Candidate(it.id, it.name) },
            defaultId = defaultCategoryId
        )
        return Resolved(result.id, result.name)
    }
}
