package com.voxapps.expenses.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voxapps.expenses.state.SortMode

/** Structural sibling to vox-notes' DateSortSheet, extended with bank/vendor filters and amount sort. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseFilterSortSheet(
    sort: SortMode,
    dateFrom: Long?,
    dateTo: Long?,
    selectedBank: String?,
    selectedVendor: String?,
    availableBanks: List<String>,
    availableVendors: List<String>,
    onApply: (SortMode, Long?, Long?, String?, String?) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    val languageManager = LocalLanguageManager.current
    val rangeState = rememberDateRangePickerState(
        initialSelectedStartDateMillis = dateFrom,
        initialSelectedEndDateMillis = dateTo
    )

    var pendingSort by remember { mutableStateOf(sort) }
    var pendingBank by remember { mutableStateOf(selectedBank) }
    var pendingVendor by remember { mutableStateOf(selectedVendor) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(languageManager.getString("sort_and_filter"), style = MaterialTheme.typography.titleMedium)

            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val sortOptions = listOf(
                    SortMode.NEWEST to "sort_newest",
                    SortMode.OLDEST to "sort_oldest",
                    SortMode.AMOUNT_ASC to "sort_amount_asc",
                    SortMode.AMOUNT_DESC to "sort_amount_desc"
                )
                sortOptions.forEach { (mode, labelKey) ->
                    FilterChip(
                        selected = pendingSort == mode,
                        onClick = { pendingSort = mode },
                        label = { Text(languageManager.getString(labelKey)) }
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            Text(languageManager.getString("bank_filter_label"), style = MaterialTheme.typography.labelLarge)
            var bankMenuExpanded by remember { mutableStateOf(false) }
            Column {
                OutlinedButton(onClick = { bankMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(pendingBank ?: languageManager.getString("all_banks"))
                }
                DropdownMenu(expanded = bankMenuExpanded, onDismissRequest = { bankMenuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(languageManager.getString("all_banks")) },
                        onClick = { pendingBank = null; bankMenuExpanded = false }
                    )
                    availableBanks.forEach { bank ->
                        DropdownMenuItem(
                            text = { Text(bank) },
                            onClick = { pendingBank = bank; bankMenuExpanded = false }
                        )
                    }
                }
            }

            Text(
                languageManager.getString("vendor_filter_label"),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 12.dp)
            )
            var vendorMenuExpanded by remember { mutableStateOf(false) }
            Column {
                OutlinedButton(onClick = { vendorMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(pendingVendor ?: languageManager.getString("all_vendors"))
                }
                DropdownMenu(expanded = vendorMenuExpanded, onDismissRequest = { vendorMenuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(languageManager.getString("all_vendors")) },
                        onClick = { pendingVendor = null; vendorMenuExpanded = false }
                    )
                    availableVendors.forEach { vendor ->
                        DropdownMenuItem(
                            text = { Text(vendor) },
                            onClick = { pendingVendor = vendor; vendorMenuExpanded = false }
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // DateRangePicker's internal calendar grid is itself a scrolling LazyColumn — nested
            // inside this sheet's own verticalScroll, it would otherwise be measured with an
            // unbounded max height and crash ("infinity maximum height constraints"). A fixed height
            // keeps it bounded regardless of the outer scroll.
            DateRangePicker(state = rangeState, modifier = Modifier.height(480.dp))

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = onClear) { Text(languageManager.getString("clear")) }
                TextButton(
                    onClick = {
                        onApply(
                            pendingSort,
                            rangeState.selectedStartDateMillis,
                            rangeState.selectedEndDateMillis,
                            pendingBank,
                            pendingVendor
                        )
                    }
                ) { Text(languageManager.getString("apply")) }
            }
        }
    }
}
