package com.voxapps.expenses.ui

import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.Icons
import com.voxapps.expenses.domain.accounts.BankAccountTree
import com.voxapps.expenses.data.BankAccount
import com.voxapps.design.VoxFullscreenSheet
import com.voxapps.design.picklist.PicklistButtonAnchor
import androidx.compose.ui.graphics.Color
import com.voxapps.expenses.state.ExpenseFilterSummary
import com.voxapps.design.filter.VoxRange
import com.voxapps.expenses.data.Category
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import com.voxapps.design.picklist.Picklist
import com.voxapps.expenses.state.FilterValue
import com.voxapps.expenses.state.OriginFilter
import com.voxapps.expenses.state.SortMode

/** Structural sibling to vox-notes' DateSortSheet, extended with bank/vendor filters and amount sort. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseFilterSortSheet(
    sort: SortMode,
    dateFrom: Long?,
    dateTo: Long?,
    selectedBank: FilterValue?,
    selectedLocation: FilterValue?,
    selectedVendor: FilterValue?,
    categories: List<Category>,
    selectedCategoryId: Long?,
    bankAccounts: List<BankAccount>,
    selectedAccountId: Long?,
    selectedCardId: Long?,
    availableCurrencies: List<String>,
    selectedCurrency: String?,
    amountBuckets: List<VoxRange>,
    selectedAmount: VoxRange?,
    availableBanks: List<String>,
    availableLocations: List<String>,
    availableVendors: List<String>,
    availableOriginDevices: List<String>,
    selectedOrigin: OriginFilter?,
    filtersActive: Boolean,
    onSortChange: (SortMode) -> Unit,
    onDateRangeChange: (Long?, Long?) -> Unit,
    onCategoryChange: (Long?) -> Unit,
    onAccountChange: (Long?) -> Unit,
    onCardChange: (Long?) -> Unit,
    onCurrencyChange: (String?) -> Unit,
    onAmountChange: (VoxRange?) -> Unit,
    onBankChange: (FilterValue?) -> Unit,
    onVendorChange: (FilterValue?) -> Unit,
    onLocationChange: (FilterValue?) -> Unit,
    onOriginChange: (OriginFilter?) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit
) {
    val languageManager = LocalLanguageManager.current
    val rangeState = rememberDateRangePickerState(
        initialSelectedStartDateMillis = dateFrom,
        initialSelectedEndDateMillis = dateTo
    )

    // The picker owns its own selection, so it is watched rather than read on the way out — there
    // is no way out to read it on.
    LaunchedEffect(rangeState.selectedStartDateMillis, rangeState.selectedEndDateMillis) {
        val from = rangeState.selectedStartDateMillis
        val to = rangeState.selectedEndDateMillis
        if (from != dateFrom || to != dateTo) onDateRangeChange(from, to)
    }

    var editingAmount by remember { mutableStateOf(false) }

    VoxFullscreenSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 20.dp, bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    languageManager.getString("sort_and_filter"),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                // Offered only when there is something to undo, so the sheet never proposes an
                // action that would do nothing.
                if (filtersActive) {
                    TextButton(onClick = onClearAll) {
                        Text(languageManager.getString("clear_all_filters"))
                    }
                }
            }

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
                        selected = sort == mode,
                        onClick = { onSortChange(mode) },
                        label = { Text(languageManager.getString(labelKey)) }
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            Picklist(
                items = categories,
                selected = categories.firstOrNull { it.id == selectedCategoryId },
                itemLabel = { it.labelled() },
                onSelect = { onCategoryChange(it.id) },
                noneLabel = languageManager.getString("all_categories"),
                onNoneSelected = { onCategoryChange(null) },
                // The dot, never the icon: the label already carries the icon, and the collapsed
                // anchor has no slot of its own to show a colour in.
                itemLeading = { cat ->
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(CategoryColors.fromStored(cat.colorArgb))
                    )
                }
            )

            Picklist(
                items = availableBanks,
                selected = selectedBank?.text?.takeIf { _ -> selectedBank?.exact == true },
                itemLabel = { it },
                onSelect = { onBankChange(FilterValue.picked(it)) },
                noneLabel = languageManager.getString("all_banks"),
                onNoneSelected = { onBankChange(null) },
                // These lists are as long as the data makes them — every vendor ever paid, every
                // place ever recorded — so they are searched rather than scrolled.
                searchPlaceholder = languageManager.getString("filter_search_hint"),
                searchAllLabel = { count -> languageManager.getString("filter_show_all_matching").format(count) },
                onSearchAll = { onBankChange(FilterValue.typed(it)) }
            )

            Picklist(
                items = availableVendors,
                selected = selectedVendor?.text?.takeIf { _ -> selectedVendor?.exact == true },
                itemLabel = { it },
                onSelect = { onVendorChange(FilterValue.picked(it)) },
                noneLabel = languageManager.getString("all_vendors"),
                onNoneSelected = { onVendorChange(null) },
                // These lists are as long as the data makes them — every vendor ever paid, every
                // place ever recorded — so they are searched rather than scrolled.
                searchPlaceholder = languageManager.getString("filter_search_hint"),
                searchAllLabel = { count -> languageManager.getString("filter_show_all_matching").format(count) },
                onSearchAll = { onVendorChange(FilterValue.typed(it)) }
            )

            Picklist(
                items = availableLocations,
                selected = selectedLocation?.text?.takeIf { _ -> selectedLocation?.exact == true },
                itemLabel = { it },
                onSelect = { onLocationChange(FilterValue.picked(it)) },
                noneLabel = languageManager.getString("all_locations"),
                onNoneSelected = { onLocationChange(null) },
                // These lists are as long as the data makes them — every vendor ever paid, every
                // place ever recorded — so they are searched rather than scrolled.
                searchPlaceholder = languageManager.getString("filter_search_hint"),
                searchAllLabel = { count -> languageManager.getString("filter_show_all_matching").format(count) },
                onSearchAll = { onLocationChange(FilterValue.typed(it)) }
            )

            // An account and a currency answer overlapping questions — an account holds one
            // currency — so choosing either lets go of the other, and whichever is not in force is
            // shown greyed rather than removed. Hiding it would leave a person hunting for a filter
            // that was there a moment ago; greyed says it is still available, just not at once.
            if (bankAccounts.isNotEmpty()) {
                // Two steps, and the second only exists because of the first: an account, then one
                // of its cards. Once a card is chosen the account is no longer a choice — it is
                // what the card belongs to — so it greys, while its ✕ stays live. That ✕ clears
                // both, because a card without the account it was picked under is not a narrowing
                // of anything the person can still see.
                val roots = BankAccountTree.rootsOf(bankAccounts)
                val accountUsable = selectedCardId == null && selectedCurrency == null
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.weight(1f)) {
                        Picklist(
                            items = roots,
                            selected = roots.firstOrNull { it.id == selectedAccountId },
                            itemLabel = { it.displayName() + (it.icon?.let { icon -> " $icon" } ?: "") },
                            onSelect = { onAccountChange(it.id) },
                            noneLabel = languageManager.getString("all_accounts"),
                            onNoneSelected = { onAccountChange(null) },
                            anchor = { label, onClick, _ ->
                                PicklistButtonAnchor(label = label, onClick = onClick, enabled = accountUsable)
                            }
                        )
                    }
                    if (selectedAccountId != null) {
                        IconButton(onClick = { onAccountChange(null) }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = languageManager.getString("clear_all_filters")
                            )
                        }
                    }
                }

                // Only where the chosen account has cards to narrow to. An account with none is
                // already as narrow as it goes.
                val cards = selectedAccountId?.let { BankAccountTree.childrenOf(it, bankAccounts) }.orEmpty()
                if (cards.isNotEmpty()) {
                    Picklist(
                        items = cards,
                        selected = cards.firstOrNull { it.id == selectedCardId },
                        itemLabel = { it.displayName() + (it.icon?.let { icon -> " $icon" } ?: "") },
                        onSelect = { onCardChange(it.id) },
                        noneLabel = languageManager.getString("all_cards_of_account"),
                        onNoneSelected = { onCardChange(null) }
                    )
                }
            }

            if (availableCurrencies.isNotEmpty()) {
                val currenciesUsable = selectedAccountId == null && selectedCardId == null
                Picklist(
                    items = availableCurrencies,
                    selected = selectedCurrency,
                    itemLabel = { it },
                    onSelect = { onCurrencyChange(it) },
                    noneLabel = languageManager.getString("all_currencies"),
                    onNoneSelected = { onCurrencyChange(null) },
                    anchor = { label, onClick, _ ->
                        PicklistButtonAnchor(label = label, onClick = onClick, enabled = currenciesUsable)
                    }
                )
            }

            // Offered only where device sync has actually delivered records from elsewhere — a
            // phone with nothing foreign has no provenance question to ask.
            if (availableOriginDevices.isNotEmpty()) {
                val originChoices = listOf(OriginFilter(null)) + availableOriginDevices.map { OriginFilter(it) }
                Picklist(
                    items = originChoices,
                    selected = selectedOrigin,
                    itemLabel = { it.deviceName ?: languageManager.getString("origin_this_device") },
                    onSelect = { onOriginChange(it) },
                    noneLabel = languageManager.getString("all_devices"),
                    onNoneSelected = { onOriginChange(null) }
                )
            }

            // Offered only where the records span enough to divide: a single bracket holding
            // everything is not a filter. See VoxRangeBuckets.
            if (amountBuckets.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedAmount == null,
                        onClick = { onAmountChange(null) },
                        label = { Text(languageManager.getString("all_amounts")) }
                    )
                    amountBuckets.forEach { bucket ->
                        FilterChip(
                            selected = selectedAmount == bucket,
                            onClick = {
                                onAmountChange(if (selectedAmount == bucket) null else bucket)
                            },
                            label = {
                                Text(ExpenseFilterSummary.amountLabel(bucket) { formatAmountPlain(it) })
                            }
                        )
                    }
                    // The brackets are read from what the data holds, which makes them a good first
                    // answer and never the only one — a question like "everything over 500" is not
                    // a bracket anybody's data would produce.
                    val custom = selectedAmount?.takeIf { it !in amountBuckets }
                    FilterChip(
                        selected = custom != null,
                        onClick = { editingAmount = true },
                        label = {
                            Text(
                                custom?.let { ExpenseFilterSummary.amountLabel(it) { v -> formatAmountPlain(v) } }
                                    ?: languageManager.getString("amount_custom")
                            )
                        }
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // DateRangePicker's internal calendar grid is itself a scrolling LazyColumn — nested
            // inside this sheet's own verticalScroll, it would otherwise be measured with an
            // unbounded max height and crash ("infinity maximum height constraints"). A fixed height
            // keeps it bounded regardless of the outer scroll.
            DateRangePicker(state = rangeState, modifier = Modifier.height(480.dp))
        }
    }

    if (editingAmount) {
        CustomAmountDialog(
            current = selectedAmount?.takeIf { it !in amountBuckets },
            onConfirm = { range -> onAmountChange(range); editingAmount = false },
            onDismiss = { editingAmount = false }
        )
    }
}

/**
 * A range somebody types, for the question the brackets do not happen to ask.
 *
 * Either end may be left empty. An empty upper end is the useful case — "everything over 500" — and
 * is carried as an infinite bound rather than as a large number, so nothing has to guess how large
 * is large enough. An empty lower end simply starts at nothing.
 */
@Composable
private fun CustomAmountDialog(
    current: VoxRange?,
    onConfirm: (VoxRange?) -> Unit,
    onDismiss: () -> Unit
) {
    val languageManager = LocalLanguageManager.current
    var from by remember { mutableStateOf(current?.from?.takeIf { it > 0.0 }?.let { formatAmountPlain(it) } ?: "") }
    var to by remember {
        mutableStateOf(current?.to?.takeIf { it.isFinite() }?.let { formatAmountPlain(it) } ?: "")
    }

    fun typed(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()
    val low = typed(from) ?: 0.0
    val high = typed(to) ?: Double.POSITIVE_INFINITY
    // Nothing to apply when both ends are empty, and nothing sensible to apply when they cross.
    val usable = (from.isNotBlank() || to.isNotBlank()) && low <= high &&
        (from.isBlank() || typed(from) != null) && (to.isBlank() || typed(to) != null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(languageManager.getString("amount_custom_title")) },
        text = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = from,
                    onValueChange = { from = it },
                    label = { Text(languageManager.getString("amount_custom_from")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = to,
                    onValueChange = { to = it },
                    label = { Text(languageManager.getString("amount_custom_to")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(VoxRange(low, high)) }, enabled = usable) {
                Text(languageManager.getString("save"))
            }
        },
        dismissButton = {
            // Clearing is here rather than as a second control: the chip that opened this is the
            // one showing the range, so this is where somebody comes to be rid of it.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (current != null) {
                    TextButton(onClick = { onConfirm(null) }) {
                        Text(languageManager.getString("clear"))
                    }
                }
                TextButton(onClick = onDismiss) { Text(languageManager.getString("cancel")) }
            }
        }
    )
}
