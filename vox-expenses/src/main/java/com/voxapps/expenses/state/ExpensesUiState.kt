package com.voxapps.expenses.state

import androidx.compose.runtime.Immutable
import com.voxapps.expenses.data.Category
import com.voxapps.expenses.data.ExpenseWithDetails

/**
 * Top-level UI state for Vox Expenses (mirrors vox-notes' NotesUiState). [Locked] is emitted only when
 * biometric reading is required and the session has expired. [Unlocked] carries the already
 * filtered/sorted data plus the active filter selection.
 */
@Immutable
sealed interface ExpensesUiState {
    data object Loading : ExpensesUiState

    data object Locked : ExpensesUiState

    @Immutable
    data class Unlocked(
        val expenses: List<ExpenseWithDetails>,
        val categories: List<Category>,
        val selectedCategoryId: Long?,
        val sort: SortMode,
        val dateFrom: Long?,
        val dateTo: Long?
    ) : ExpensesUiState {
        val isDateFilterActive: Boolean get() = dateFrom != null || dateTo != null
    }
}
