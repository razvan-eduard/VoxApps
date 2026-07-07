package com.voxapps.notes.state

import androidx.compose.runtime.Immutable
import com.voxapps.notes.data.Category
import com.voxapps.notes.data.NoteWithCategory

/**
 * Top-level UI state for VoxNotes. [Locked] is emitted only when biometric reading is required and
 * the session has expired — the AuthGate renders the lock screen for it. [Unlocked] carries the
 * already filtered/sorted data plus the active filter selection.
 */
@Immutable
sealed interface NotesUiState {
    data object Loading : NotesUiState

    data object Locked : NotesUiState

    @Immutable
    data class Unlocked(
        val notes: List<NoteWithCategory>,
        val categories: List<Category>,
        val selectedCategoryId: Long?,
        val sort: SortMode,
        val dateFrom: Long?,
        val dateTo: Long?
    ) : NotesUiState {
        val isDateFilterActive: Boolean get() = dateFrom != null || dateTo != null
    }
}
