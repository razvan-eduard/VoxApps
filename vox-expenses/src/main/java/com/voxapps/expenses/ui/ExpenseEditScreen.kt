package com.voxapps.expenses.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.voxapps.expenses.data.Category
import com.voxapps.expenses.data.Expense
import com.voxapps.expenses.data.ExpenseLineItem
import com.voxapps.expenses.data.ExpenseWithDetails
import com.voxapps.expenses.state.ExpensesStateManager
import java.text.DateFormat
import java.util.Date

private data class LineItemDraft(
    var name: String,
    var quantityText: String,
    var unitPriceText: String
) {
    val subtotal: Double get() = (quantityText.toDoubleOrNull() ?: 0.0) * (unitPriceText.toDoubleOrNull() ?: 0.0)
}

/**
 * Add/edit screen for a single expense. Only [totalText] is mandatory to save — everything else is
 * optional. Whenever the line-item list is non-empty, the total is recomputed from item subtotals on
 * every item edit (still a plain text field the user can type over afterward); an empty item list
 * leaves the total fully free-form.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseEditScreen(
    existing: ExpenseWithDetails?,
    categories: List<Category>,
    defaultCurrency: String,
    stateManager: ExpensesStateManager,
    onDone: () -> Unit
) {
    val languageManager = LocalLanguageManager.current

    var title by remember { mutableStateOf(existing?.expense?.title ?: "") }
    var totalText by remember { mutableStateOf(existing?.expense?.totalAmount?.let { "%.2f".format(it) } ?: "") }
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
            existing?.items?.forEach { add(LineItemDraft(it.name, "%.2f".format(it.quantity), "%.2f".format(it.unitPrice))) }
        }
    }

    fun recomputeTotalFromItems() {
        if (items.isNotEmpty()) {
            totalText = "%.2f".format(items.sumOf { it.subtotal })
        }
    }

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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = totalText,
                        onValueChange = { totalText = it },
                        label = { Text(languageManager.getString("expense_amount")) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        enabled = items.isEmpty(),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = currency,
                        onValueChange = { currency = it.uppercase().take(3) },
                        label = { Text(languageManager.getString("expense_currency")) },
                        modifier = Modifier.weight(0.6f)
                    )
                }
                if (items.isNotEmpty()) {
                    Text(
                        languageManager.getString("total_computed_from_items"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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

            items(items.size) { index ->
                val draft = items[index]
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = draft.name,
                        onValueChange = { items[index] = draft.copy(name = it) },
                        label = { Text(languageManager.getString("item_name")) },
                        modifier = Modifier.weight(1.4f)
                    )
                    OutlinedTextField(
                        value = draft.quantityText,
                        onValueChange = { items[index] = draft.copy(quantityText = it); recomputeTotalFromItems() },
                        label = { Text(languageManager.getString("item_qty")) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(0.7f)
                    )
                    OutlinedTextField(
                        value = draft.unitPriceText,
                        onValueChange = { items[index] = draft.copy(unitPriceText = it); recomputeTotalFromItems() },
                        label = { Text(languageManager.getString("item_unit_price")) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(0.9f)
                    )
                    IconButton(onClick = {
                        items.removeAt(index)
                        recomputeTotalFromItems()
                        if (items.isEmpty()) { /* leave totalText as last computed value, now free-editable */ }
                    }) {
                        Icon(Icons.Filled.Delete, contentDescription = languageManager.getString("delete"))
                    }
                }
            }

            item {
                Button(
                    onClick = {
                        val total = totalText.toDoubleOrNull() ?: return@Button
                        val lineItems = items
                            .filter { it.name.isNotBlank() }
                            .map {
                                ExpenseLineItem(
                                    expenseId = existing?.expense?.id ?: 0,
                                    name = it.name.trim(),
                                    quantity = it.quantityText.toDoubleOrNull() ?: 1.0,
                                    unitPrice = it.unitPriceText.toDoubleOrNull() ?: 0.0
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
                    enabled = totalText.toDoubleOrNull() != null,
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
