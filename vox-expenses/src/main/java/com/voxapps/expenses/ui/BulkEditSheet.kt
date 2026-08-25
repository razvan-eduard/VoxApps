package com.voxapps.expenses.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.voxapps.design.PaperTapField
import com.voxapps.design.picklist.Picklist
import com.voxapps.design.picklist.VoxNameDialog
import com.voxapps.expenses.data.BankAccount
import com.voxapps.expenses.data.Category
import com.voxapps.expenses.data.ExpenseWithDetails
import com.voxapps.expenses.data.FieldVocabularies
import com.voxapps.expenses.data.TransactionDirection
import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.expenses.domain.bulk.BulkEdit
import com.voxapps.expenses.domain.bulk.Shared
import com.voxapps.expenses.state.ExpensesStateManager
import kotlinx.coroutines.launch

/**
 * One value, written to every record that was picked.
 *
 * The same fields in the same order as a single record's editor, and read the same way: a field
 * shows what the records say when they all say the same thing, and says so plainly when they do
 * not. Nothing is written unless it is set here — the cost of a stray field is twenty wrong records
 * and no memory of what they used to say, so "leave it alone" has to be the state a form like this
 * rests in.
 *
 * What is missing from it matters as much as what is in it. There is no amount, no date and no
 * currency, because those are facts about one payment each — a form that offered to set twenty
 * amounts to the same figure would be offering to invent nineteen of them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BulkEditSheet(
    records: List<ExpenseWithDetails>,
    categories: List<Category>,
    accounts: List<BankAccount>,
    locations: List<String>,
    stateManager: ExpensesStateManager,
    settings: ExpensesSettings,
    onApply: (BulkEdit) -> Unit,
    onDismiss: () -> Unit
) {
    val languageManager = LocalLanguageManager.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val names = rememberFieldNameLists(stateManager, settings)
    val scope = rememberCoroutineScope()

    var categoryId by remember { mutableStateOf<Long?>(null) }
    var vendor by remember { mutableStateOf<String?>(null) }
    var bank by remember { mutableStateOf<String?>(null) }
    var accountId by remember { mutableStateOf<Long?>(null) }
    var location by remember { mutableStateOf<String?>(null) }
    var direction by remember { mutableStateOf<TransactionDirection?>(null) }
    var namingVendor by remember { mutableStateOf(false) }
    var namingBank by remember { mutableStateOf(false) }
    var namingLocation by remember { mutableStateOf(false) }

    val edit = BulkEdit(
        categoryId = categoryId,
        vendor = vendor,
        bank = bank,
        bankAccountId = accountId,
        location = location,
        direction = direction
    )

    val expenses = remember(records) { records.map { it.expense } }
    val sharedVendor = remember(expenses) { Shared.across(expenses) { it.vendor?.takeIf { v -> v.isNotBlank() } } }
    val sharedBank = remember(expenses) { Shared.across(expenses) { it.bank?.takeIf { v -> v.isNotBlank() } } }
    val sharedAccount = remember(expenses) { Shared.across(expenses) { it.bankAccountId } }
    val sharedLocation = remember(expenses) { Shared.across(expenses) { it.location?.takeIf { v -> v.isNotBlank() } } }
    val sharedCategory = remember(expenses) { Shared.across(expenses) { it.categoryId } }
    val sharedDirection = remember(expenses) { Shared.across(expenses) { it.direction } }

    val unchanged = languageManager.getString("bulk_edit_unchanged")
    val multiple = languageManager.getString("bulk_edit_multiple")
    val none = languageManager.getString("none")
    val offered = remember(accounts) { accounts.filter { !it.archived } }

    /** What a field shows, and whether that is a value or a report about the selection. */
    fun shown(chosen: String?, shared: Shared<String>): Pair<String, Boolean> = when {
        chosen != null -> chosen to false
        !shared.agreed -> multiple to true
        shared.value != null -> shared.value to false
        else -> none to true
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                languageManager.counted("bulk_edit_title", records.size),
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                languageManager.getString("bulk_edit_explainer"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val (vendorText, vendorIsPlaceholder) = shown(vendor, sharedVendor)
            Picklist(
                items = names.vendors,
                selected = vendor,
                itemLabel = { it },
                onSelect = { vendor = it },
                noneLabel = unchanged,
                onNoneSelected = { vendor = null },
                searchPlaceholder = languageManager.getString("filter_search_hint"),
                actionLabel = languageManager.getString("expense_vendor_new"),
                onAction = { namingVendor = true },
                anchor = { _, onClick ->
                    BulkField(languageManager.getString("expense_vendor"), vendorText, vendorIsPlaceholder, onClick)
                }
            )

            val (bankText, bankIsPlaceholder) = shown(bank, sharedBank)
            Picklist(
                items = names.banks,
                selected = bank,
                itemLabel = { it },
                onSelect = { bank = it },
                noneLabel = unchanged,
                onNoneSelected = { bank = null },
                searchPlaceholder = languageManager.getString("filter_search_hint"),
                extraWhileSearching = names.banksKnown,
                actionLabel = languageManager.getString("account_bank_new"),
                onAction = { namingBank = true },
                anchor = { _, onClick ->
                    BulkField(languageManager.getString("expense_bank"), bankText, bankIsPlaceholder, onClick)
                }
            )

            val chosenAccount = offered.firstOrNull { it.id == accountId }
            val (accountText, accountIsPlaceholder) = when {
                chosenAccount != null -> chosenAccount.displayName() to false
                !sharedAccount.agreed -> multiple to true
                else -> (accounts.firstOrNull { it.id == sharedAccount.value }?.displayName() ?: none) to
                    (sharedAccount.value == null)
            }
            Picklist(
                items = offered,
                selected = chosenAccount,
                itemLabel = { it.displayName() },
                onSelect = { accountId = it.id },
                noneLabel = unchanged,
                onNoneSelected = { accountId = null },
                searchPlaceholder = languageManager.getString("filter_search_hint"),
                anchor = { _, onClick ->
                    BulkField(
                        languageManager.getString("account_filter_label"),
                        accountText,
                        accountIsPlaceholder,
                        onClick
                    )
                }
            )

            val (locationText, locationIsPlaceholder) = shown(location, sharedLocation)
            Picklist(
                items = locations,
                selected = location,
                itemLabel = { it },
                onSelect = { location = it },
                noneLabel = unchanged,
                onNoneSelected = { location = null },
                searchPlaceholder = languageManager.getString("filter_search_hint"),
                actionLabel = languageManager.getString("bulk_edit_location_new"),
                onAction = { namingLocation = true },
                anchor = { _, onClick ->
                    BulkField(languageManager.getString("expense_location"), locationText, locationIsPlaceholder, onClick)
                }
            )

            val chosenCategory = categories.firstOrNull { it.id == categoryId }
            val (categoryText, categoryIsPlaceholder) = when {
                chosenCategory != null -> chosenCategory.labelled() to false
                !sharedCategory.agreed -> multiple to true
                else -> (categories.firstOrNull { it.id == sharedCategory.value }?.labelled() ?: none) to
                    (sharedCategory.value == null)
            }
            Picklist(
                items = categories,
                selected = chosenCategory,
                itemLabel = { it.labelled() },
                onSelect = { categoryId = it.id },
                noneLabel = unchanged,
                onNoneSelected = { categoryId = null },
                itemLeading = { cat ->
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(CategoryColors.fromStored(cat.colorArgb))
                    )
                },
                anchor = { _, onClick ->
                    BulkField(
                        languageManager.getString("expense_category"),
                        categoryText,
                        categoryIsPlaceholder,
                        onClick
                    )
                }
            )

            fun directionLabel(value: TransactionDirection) = languageManager.getString(
                if (value == TransactionDirection.OUTGOING) "transaction_direction_outgoing"
                else "transaction_direction_incoming"
            )
            val (directionText, directionIsPlaceholder) = when {
                direction != null -> directionLabel(direction!!) to false
                !sharedDirection.agreed -> multiple to true
                else -> directionLabel(sharedDirection.value ?: TransactionDirection.OUTGOING) to false
            }
            Picklist(
                items = listOf(TransactionDirection.OUTGOING, TransactionDirection.INCOMING),
                selected = direction,
                itemLabel = { directionLabel(it) },
                onSelect = { direction = it },
                noneLabel = unchanged,
                onNoneSelected = { direction = null },
                anchor = { _, onClick ->
                    BulkField(
                        languageManager.getString("expense_direction"),
                        directionText,
                        directionIsPlaceholder,
                        onClick
                    )
                }
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) { Text(languageManager.getString("cancel")) }
                Button(
                    onClick = { onApply(edit) },
                    enabled = !edit.isEmpty,
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text(languageManager.getString("bulk_edit_apply"))
                }
            }
        }
    }

    if (namingVendor) {
        VoxNameDialog(
            title = languageManager.getString("expense_vendor_new"),
            label = languageManager.getString("expense_vendor"),
            saveLabel = languageManager.getString("save"),
            cancelLabel = languageManager.getString("cancel"),
            onNamed = { named ->
                vendor = named
                scope.launch { stateManager.addVocabularyTerm(FieldVocabularies.VOCAB_VENDOR, named) }
            },
            onDismiss = { namingVendor = false }
        )
    }
    if (namingBank) {
        VoxNameDialog(
            title = languageManager.getString("account_bank_new"),
            label = languageManager.getString("expense_bank"),
            saveLabel = languageManager.getString("save"),
            cancelLabel = languageManager.getString("cancel"),
            onNamed = { named ->
                bank = named
                scope.launch { stateManager.addVocabularyTerm(FieldVocabularies.VOCAB_BANK, named) }
            },
            onDismiss = { namingBank = false }
        )
    }
    if (namingLocation) {
        VoxNameDialog(
            title = languageManager.getString("bulk_edit_location_new"),
            label = languageManager.getString("expense_location"),
            saveLabel = languageManager.getString("save"),
            cancelLabel = languageManager.getString("cancel"),
            onNamed = { named -> location = named },
            onDismiss = { namingLocation = false }
        )
    }
}

/** A field of the record editor, standing in for as many records as were chosen. */
@Composable
private fun BulkField(label: String, value: String, placeholder: Boolean, onClick: () -> Unit) {
    PaperTapField(
        label = label,
        value = value,
        onClick = onClick,
        placeholder = placeholder,
        trailingIcon = {
            Icon(
                Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    )
}
