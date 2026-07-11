package com.voxapps.expenses.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voxapps.expenses.data.Category
import com.voxapps.expenses.data.Expense
import com.voxapps.expenses.data.ExpenseLineItem
import com.voxapps.expenses.data.ExpenseWithDetails
import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.expenses.state.ExpensesStateManager
import java.text.DateFormat
import java.util.Date

/** Green isn't a Material3 color role — a fixed swatch reads clearly as "confirm" regardless of theme. */
private val ConfirmGreen = Color(0xFF4CAF50)

private data class LineItemDraft(
    var name: String,
    var quantityText: String,
    var unitPriceText: String,
    var netAmountText: String = "",
    var vatAmountText: String = "",
    var grossAmountText: String = ""
)

private fun LineItemDraft.subtotal(useComma: Boolean): Double =
    (parseDecimalOrNull(quantityText, useComma) ?: 0.0) * (parseDecimalOrNull(unitPriceText, useComma) ?: 0.0)

/**
 * Add/edit screen for a single expense. Only [totalText] is mandatory to save — everything else is
 * optional. The total is NEVER force-recomputed from line items — an LLM-parsed receipt's printed
 * total is often correct even when its extracted item list is incomplete or wrong (see
 * [com.voxapps.expenses.domain.llm.ExpenseScanCleanupPromptBuilder]), so silently overwriting one with
 * the other would destroy real data. Instead, when the (committed) items don't sum to the total, the
 * total footer row below the item cards is flagged with a red background — the user decides which is
 * right and edits either one manually.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseEditScreen(
    existing: ExpenseWithDetails?,
    categories: List<Category>,
    defaultCurrency: String,
    vatDisplayEnabled: Boolean,
    decimalSeparator: String,
    stateManager: ExpensesStateManager,
    onDone: () -> Unit
) {
    val languageManager = LocalLanguageManager.current
    val useComma = decimalSeparator == ExpensesSettings.DECIMAL_COMMA

    // System back mirrors the top-bar arrow: return to the expense list without saving.
    BackHandler { onDone() }

    var title by remember { mutableStateOf(existing?.expense?.title ?: "") }
    var totalText by remember { mutableStateOf(existing?.expense?.totalAmount?.let { formatDecimal(it, useComma) } ?: "") }
    var currency by remember { mutableStateOf(existing?.expense?.currencyCode ?: defaultCurrency) }
    var vendor by remember { mutableStateOf(existing?.expense?.vendor ?: "") }
    var bank by remember { mutableStateOf(existing?.expense?.bank ?: "") }
    var location by remember { mutableStateOf(existing?.expense?.location ?: "") }
    var comments by remember { mutableStateOf(existing?.expense?.comments ?: "") }
    var dateTime by remember { mutableStateOf(existing?.expense?.dateTime ?: System.currentTimeMillis()) }
    var categoryId by remember { mutableStateOf(existing?.expense?.categoryId) }
    var categoryMenuExpanded by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }

    val items = remember {
        mutableStateListOf<LineItemDraft>().apply {
            existing?.items?.forEach {
                add(
                    LineItemDraft(
                        name = it.name,
                        quantityText = formatDecimal(it.quantity, useComma),
                        unitPriceText = formatDecimal(it.unitPrice, useComma),
                        netAmountText = it.netAmount?.let { v -> formatDecimal(v, useComma) } ?: "",
                        vatAmountText = it.vatAmount?.let { v -> formatDecimal(v, useComma) } ?: "",
                        grossAmountText = it.grossAmount?.let { v -> formatDecimal(v, useComma) } ?: ""
                    )
                )
            }
        }
    }

    // Only committed item values count toward the sum — a row mid-edit (local draft, not yet
    // confirmed via the row's check icon) doesn't move this until confirmed.
    val itemsSum = items.sumOf { it.subtotal(useComma) }
    val totalMismatch = items.isNotEmpty() &&
        (parseDecimalOrNull(totalText, useComma)?.let { kotlin.math.abs(it - itemsSum) > 0.01 } ?: false)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(languageManager.getString(if (existing != null) "edit_expense" else "new_expense")) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = languageManager.getString("back"))
                    }
                },
                actions = {
                    if (existing != null) {
                        IconButton(onClick = {
                            stateManager.deleteExpense(existing.expense)
                            onDone()
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = languageManager.getString("delete"))
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(languageManager.getString("expense_title_optional")) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                OutlinedTextField(
                    value = vendor,
                    onValueChange = { vendor = it },
                    label = { Text(languageManager.getString("expense_vendor")) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                OutlinedTextField(
                    value = bank,
                    onValueChange = { bank = it },
                    label = { Text(languageManager.getString("expense_bank")) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text(languageManager.getString("expense_location")) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.CalendarMonth, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(dateTime)))
                }
            }
            item {
                Column {
                    OutlinedButton(onClick = { categoryMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(categories.firstOrNull { it.id == categoryId }?.name ?: languageManager.getString("none"))
                    }
                    DropdownMenu(expanded = categoryMenuExpanded, onDismissRequest = { categoryMenuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text(languageManager.getString("none")) },
                            onClick = { categoryId = null; categoryMenuExpanded = false }
                        )
                        categories.forEach { cat ->
                            DropdownMenuItem(
                                text = { Text(cat.name) },
                                onClick = { categoryId = cat.id; categoryMenuExpanded = false }
                            )
                        }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = comments,
                    onValueChange = { comments = it },
                    label = { Text(languageManager.getString("expense_comments")) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp)) }

            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(languageManager.getString("line_items"), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = {
                        items.add(LineItemDraft("", "1", ""))
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Text(languageManager.getString("add_item"))
                    }
                }
            }

            if (items.isNotEmpty()) {
                item {
                    LineItemHeaderRow(vatDisplayEnabled, languageManager)
                }
            }

            items(items.size) { index ->
                LineItemCard(
                    item = items[index],
                    vatDisplayEnabled = vatDisplayEnabled,
                    languageManager = languageManager,
                    onCommit = { items[index] = it },
                    onDelete = { items.removeAt(index) }
                )
            }

            item {
                val footerBackground = if (totalMismatch) MaterialTheme.colorScheme.errorContainer else Color.Transparent
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(footerBackground)
                        .padding(if (totalMismatch) 8.dp else 0.dp)
                ) {
                    OutlinedTextField(
                        value = totalText,
                        onValueChange = { totalText = it },
                        label = { Text(languageManager.getString("expense_amount")) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        isError = totalMismatch,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = currency,
                        onValueChange = { currency = it.uppercase().take(3) },
                        label = { Text(languageManager.getString("expense_currency")) },
                        modifier = Modifier.weight(0.6f)
                    )
                }
            }

            item {
                Button(
                    onClick = {
                        val total = parseDecimalOrNull(totalText, useComma) ?: return@Button
                        val lineItems = items
                            .filter { it.name.isNotBlank() }
                            .map {
                                ExpenseLineItem(
                                    expenseId = existing?.expense?.id ?: 0,
                                    name = it.name.trim(),
                                    quantity = parseDecimalOrNull(it.quantityText, useComma) ?: 1.0,
                                    unitPrice = parseDecimalOrNull(it.unitPriceText, useComma) ?: 0.0,
                                    netAmount = parseDecimalOrNull(it.netAmountText, useComma),
                                    vatAmount = parseDecimalOrNull(it.vatAmountText, useComma),
                                    grossAmount = parseDecimalOrNull(it.grossAmountText, useComma)
                                )
                            }
                        if (existing != null) {
                            stateManager.updateExpense(
                                existing.expense.copy(
                                    title = title,
                                    totalAmount = total,
                                    currencyCode = currency.ifBlank { defaultCurrency },
                                    vendor = vendor,
                                    bank = bank,
                                    location = location,
                                    dateTime = dateTime,
                                    comments = comments,
                                    categoryId = categoryId
                                ),
                                lineItems
                            )
                        } else {
                            stateManager.addExpense(
                                title = title,
                                totalAmount = total,
                                currencyCode = currency.ifBlank { defaultCurrency },
                                vendor = vendor,
                                bank = bank,
                                location = location,
                                dateTime = dateTime,
                                comments = comments,
                                categoryId = categoryId,
                                items = lineItems
                            )
                        }
                        onDone()
                    },
                    enabled = parseDecimalOrNull(totalText, useComma) != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(languageManager.getString("save"))
                }
            }
        }
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = dateTime)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { dateTime = it }
                    showDatePicker = false
                }) { Text(languageManager.getString("apply")) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text(languageManager.getString("cancel")) }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

/** Column-label caption row above the line-item cards — mirrors each card's field weights so labels line up. */
@Composable
private fun LineItemHeaderRow(
    vatDisplayEnabled: Boolean,
    languageManager: com.voxapps.expenses.domain.localization.LanguageManager
) {
    val labelStyle = MaterialTheme.typography.labelMedium
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            Text(languageManager.getString("item_name"), style = labelStyle, color = labelColor, modifier = Modifier.weight(1.4f))
            Text(languageManager.getString("item_qty"), style = labelStyle, color = labelColor, modifier = Modifier.weight(0.7f))
            Text(languageManager.getString("item_unit_price"), style = labelStyle, color = labelColor, modifier = Modifier.weight(0.9f))
            Box(modifier = Modifier.width(40.dp))
        }
        if (vatDisplayEnabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
                Text(languageManager.getString("item_net_amount"), style = labelStyle, color = labelColor, modifier = Modifier.weight(1f))
                Text(languageManager.getString("item_vat_amount"), style = labelStyle, color = labelColor, modifier = Modifier.weight(1f))
                Text(languageManager.getString("item_gross_amount"), style = labelStyle, color = labelColor, modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * One line item as a card (mirrors [ExpenseCard]'s container style). Tapping the card in display mode
 * enters edit mode with a local draft — keystrokes only touch this draft, never [item] itself, so the
 * total-mismatch calculation (driven by the committed [items] list in the parent) doesn't react until
 * the green check confirms the draft via [onCommit]. The red X discards the draft unchanged.
 */
@Composable
private fun LineItemCard(
    item: LineItemDraft,
    vatDisplayEnabled: Boolean,
    languageManager: com.voxapps.expenses.domain.localization.LanguageManager,
    onCommit: (LineItemDraft) -> Unit,
    onDelete: () -> Unit
) {
    var isEditing by remember { mutableStateOf(false) }
    var draft by remember(isEditing) { mutableStateOf(item) }

    val cardColors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)

    if (!isEditing) {
        Card(
            onClick = { draft = item; isEditing = true },
            colors = cardColors,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        item.name.ifBlank { "—" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1.4f)
                    )
                    Text(item.quantityText, modifier = Modifier.weight(0.7f))
                    Text(item.unitPriceText, modifier = Modifier.weight(0.9f))
                    IconButton(onClick = onDelete, modifier = Modifier.width(40.dp)) {
                        Icon(Icons.Filled.Delete, contentDescription = languageManager.getString("delete"))
                    }
                }
                if (vatDisplayEnabled) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text(item.netAmountText.ifBlank { "—" }, modifier = Modifier.weight(1f))
                        Text(item.vatAmountText.ifBlank { "—" }, modifier = Modifier.weight(1f))
                        Text(item.grossAmountText.ifBlank { "—" }, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    } else {
        Card(colors = cardColors, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    InlineEditField(
                        value = draft.name,
                        onValueChange = { draft = draft.copy(name = it) },
                        modifier = Modifier.weight(1.4f)
                    )
                    InlineEditField(
                        value = draft.quantityText,
                        onValueChange = { draft = draft.copy(quantityText = it) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(0.7f)
                    )
                    InlineEditField(
                        value = draft.unitPriceText,
                        onValueChange = { draft = draft.copy(unitPriceText = it) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(0.9f)
                    )
                }
                if (vatDisplayEnabled) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        InlineEditField(
                            value = draft.netAmountText,
                            onValueChange = { draft = draft.copy(netAmountText = it) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f)
                        )
                        InlineEditField(
                            value = draft.vatAmountText,
                            onValueChange = { draft = draft.copy(vatAmountText = it) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f)
                        )
                        InlineEditField(
                            value = draft.grossAmountText,
                            onValueChange = { draft = draft.copy(grossAmountText = it) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                ) {
                    IconButton(onClick = { isEditing = false }) {
                        Icon(Icons.Filled.Close, contentDescription = languageManager.getString("cancel"), tint = MaterialTheme.colorScheme.error)
                    }
                    IconButton(onClick = { onCommit(draft); isEditing = false }) {
                        Icon(Icons.Filled.Check, contentDescription = languageManager.getString("save"), tint = ConfirmGreen)
                    }
                }
            }
        }
    }
}

/** A single inline-editable cell: lighter background box (vs. the card's darker container) around a BasicTextField. */
@Composable
private fun InlineEditField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default
) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 6.dp, vertical = 6.dp)
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = TextStyle.Default.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = keyboardOptions,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
