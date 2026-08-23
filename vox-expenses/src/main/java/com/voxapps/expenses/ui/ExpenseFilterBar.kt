package com.voxapps.expenses.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voxapps.design.filter.VoxFilterButton
import com.voxapps.design.filter.VoxFilterSummary
import com.voxapps.expenses.state.ExpenseFilterSummary
import com.voxapps.expenses.state.ExpensesStateManager
import com.voxapps.expenses.state.ExpensesUiState
import com.voxapps.expenses.state.sortKeyOf
import java.text.DateFormat
import java.util.Date

/**
 * The one control for a narrowed list — what is in force, the way to change it, the way to undo it.
 *
 * Shared by the list and the reports rather than copied into each, and reading from the same state,
 * so a narrowing made in one holds in the other. That is the point of a report: it answers a
 * question about the records you are looking at, and a report that quietly widened the question back
 * out would answer a different one.
 */
@Composable
fun ExpenseFilterBar(
    state: ExpensesUiState.Unlocked,
    stateManager: ExpensesStateManager,
    modifier: Modifier = Modifier
) {
    val languageManager = LocalLanguageManager.current
    var sheetOpen by remember { mutableStateOf(false) }
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.SHORT) }

    // A card answers for itself; an account answers for its cards too. Whichever was chosen last is
    // the narrower one, and the narrower one is what the button names.
    val narrowedTo = state.selectedCardId ?: state.selectedAccountId
    val filtersActive = ExpenseFilterSummary.anyActive(
        state.selectedCategoryId, state.selectedBank, state.selectedVendor, state.selectedLocation,
        state.selectedAmount, narrowedTo, state.selectedCurrency,
        state.dateFrom, state.dateTo, state.sort
    )

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        VoxFilterButton(
            label = VoxFilterSummary.of(
                ExpenseFilterSummary.parts(
                    category = state.categories.firstOrNull { it.id == state.selectedCategoryId }?.labelled(),
                    bank = state.selectedBank,
                    vendor = state.selectedVendor,
                    location = state.selectedLocation,
                    amount = state.selectedAmount,
                    account = state.bankAccounts.firstOrNull { it.id == narrowedTo }?.displayName(),
                    currency = state.selectedCurrency,
                    dateFrom = state.dateFrom,
                    dateTo = state.dateTo,
                    sort = state.sort,
                    formatDate = { dateFormat.format(Date(it)) },
                    formatAmount = { formatAmountPlain(it) },
                    sortLabel = { languageManager.getString(sortKeyOf(it)) }
                ),
                whenNothingActive = languageManager.getString("all_expenses")
            ),
            active = filtersActive,
            onClick = { sheetOpen = true },
            onClear = { stateManager.clearAllFilters() },
            clearContentDescription = languageManager.getString("clear_all_filters")
        )
    }

    if (sheetOpen) {
        ExpenseFilterSortSheet(
            sort = state.sort,
            dateFrom = state.dateFrom,
            dateTo = state.dateTo,
            selectedBank = state.selectedBank,
            selectedLocation = state.selectedLocation,
            selectedVendor = state.selectedVendor,
            categories = state.categories,
            selectedCategoryId = state.selectedCategoryId,
            bankAccounts = state.bankAccounts,
            selectedAccountId = state.selectedAccountId,
            selectedCardId = state.selectedCardId,
            availableCurrencies = state.availableCurrencies,
            selectedCurrency = state.selectedCurrency,
            amountBuckets = state.amountBuckets,
            selectedAmount = state.selectedAmount,
            availableBanks = state.availableBanks,
            availableLocations = state.availableLocations,
            availableVendors = state.availableVendors,
            filtersActive = filtersActive,
            onSortChange = { stateManager.setSort(it) },
            onDateRangeChange = { from, to -> stateManager.setDateFilter(from, to) },
            onCategoryChange = { stateManager.setCategoryFilter(it) },
            onAccountChange = { stateManager.setAccountFilter(it) },
            onCardChange = { stateManager.setCardFilter(it) },
            onCurrencyChange = { stateManager.setCurrencyFilter(it) },
            onAmountChange = { stateManager.setAmountFilter(it) },
            onBankChange = { stateManager.setBankFilter(it) },
            onVendorChange = { stateManager.setVendorFilter(it) },
            onLocationChange = { stateManager.setLocationFilter(it) },
            onClearAll = { stateManager.clearAllFilters() },
            onDismiss = { sheetOpen = false }
        )
    }
}
