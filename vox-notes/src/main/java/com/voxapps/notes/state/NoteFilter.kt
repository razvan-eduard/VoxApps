package com.voxapps.notes.state

import com.voxapps.notes.data.NoteWithCategory

enum class SortMode { NEWEST, OLDEST }

/**
 * Pure filtering/sorting of notes by category + date range + sort direction. Kept out of Room so
 * it's trivially unit-testable and matches vox-commander's "pure logic in state layer" approach.
 */
object NoteFilter {
    fun apply(
        notes: List<NoteWithCategory>,
        categoryId: Long?,
        dateFrom: Long?,
        dateTo: Long?,
        sort: SortMode
    ): List<NoteWithCategory> {
        val filtered = notes.filter { nwc ->
            val n = nwc.note
            (categoryId == null || n.categoryId == categoryId) &&
                (dateFrom == null || n.createdAt >= dateFrom) &&
                (dateTo == null || n.createdAt <= dateTo)
        }
        return when (sort) {
            SortMode.NEWEST -> filtered.sortedByDescending { it.note.createdAt }
            SortMode.OLDEST -> filtered.sortedBy { it.note.createdAt }
        }
    }
}
