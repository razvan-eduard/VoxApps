package com.voxapps.expenses.state

import com.voxapps.expenses.data.BankAccount
import com.voxapps.design.filter.VoxRange
import androidx.compose.runtime.Immutable
import com.voxapps.expenses.data.Category

/**
 * Top-level UI state for Vox Expenses (mirrors vox-notes' NotesUiState). [Locked] is emitted only
 * when biometric reading is required and the session has expired. [Unlocked] carries the active
 * filter selection and the pickers' vocabularies — never the rows themselves: the scrolling list
 * pages them through [ExpensesStateManager.pagedExpenses], and the screens that hold a whole list
 * (reports, the calendar layout, bulk selection) collect [ExpensesStateManager.filteredExpenses]
 * only while they are on screen.
 */
@Immutable
sealed interface ExpensesUiState {
    data object Loading : ExpensesUiState

    data object Locked : ExpensesUiState

    @Immutable
    data class Unlocked(
        val categories: List<Category>,
        val selectedCategoryId: Long?,
        val sort: SortMode,
        val isGridView: Boolean,
        val selectedDateMillis: Long,
        val dateFrom: Long?,
        val dateTo: Long?,
        val selectedBank: FilterValue? = null,
        val selectedLocation: FilterValue? = null,
        val selectedVendor: FilterValue? = null,
        val selectedAmount: VoxRange? = null,
        val amountBuckets: List<VoxRange> = emptyList(),
        val selectedAccountId: Long? = null,
        val selectedCardId: Long? = null,
        val selectedCurrency: String? = null,
        /** Narrowed to records with something missing — see [com.voxapps.expenses.domain.health.ExpenseGaps]. */
        val onlyNeedsAttention: Boolean = false,
        /** Narrowed to one device's records — see [OriginFilter]. Null shows everything. */
        val selectedOrigin: OriginFilter? = null,
        /** The devices any record arrived from — the provenance filter's vocabulary; empty on a
         *  phone nothing has synced to, which is what hides the whole control. */
        val availableOriginDevices: List<String> = emptyList(),
        /** Whether lists label records that arrived from another device — see
         *  [com.voxapps.expenses.data.preferences.ExpensesSettings.showSyncProvenance]. */
        val showSyncProvenance: Boolean = false,
        /** The stored device-sync level — see
         *  [com.voxapps.expenses.data.preferences.ExpensesSettings.syncLevel]. */
        val syncLevel: String = com.voxapps.datahygiene.SyncLevel.MANUAL.name,
        val bankAccounts: List<BankAccount> = emptyList(),
        val availableCurrencies: List<String> = emptyList(),
        val availableBanks: List<String> = emptyList(),
        val availableLocations: List<String> = emptyList(),
        val availableVendors: List<String> = emptyList(),
        val nextScheduledDedupMillis: Long? = null
    ) : ExpensesUiState {
        val isDateFilterActive: Boolean get() = dateFrom != null || dateTo != null
        val isAmountSort: Boolean get() = sort == SortMode.AMOUNT_ASC || sort == SortMode.AMOUNT_DESC
    }
}
