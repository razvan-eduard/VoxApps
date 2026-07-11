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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.voxapps.design.DoubleBackToExitHandler
import com.voxapps.expenses.data.ExpenseWithDetails
import com.voxapps.expenses.domain.llm.ExpenseScanRequestSender
import com.voxapps.expenses.state.ExpensesStateManager
import com.voxapps.expenses.state.ExpensesUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpensesScreen(
    state: ExpensesUiState.Unlocked,
    stateManager: ExpensesStateManager,
    onAddExpense: () -> Unit,
    onEditExpense: (ExpenseWithDetails) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenReports: () -> Unit
) {
    val languageManager = LocalLanguageManager.current
    val context = LocalContext.current

    DoubleBackToExitHandler(message = languageManager.getString("press_back_again_to_exit"))

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(languageManager.getString("expenses_title")) },
                actions = {
                    IconButton(onClick = onOpenReports) {
                        Icon(Icons.Filled.Assessment, contentDescription = languageManager.getString("reports_title"))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = languageManager.getString("settings"))
                    }
                }
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                ExtendedFloatingActionButton(
                    onClick = { ExpenseScanRequestSender.send(context) },
                    icon = { Icon(Icons.Filled.DocumentScanner, contentDescription = null) },
                    text = { Text(languageManager.getString("scan_receipt")) },
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                FloatingActionButton(onClick = onAddExpense) {
                    Icon(Icons.Filled.Add, contentDescription = languageManager.getString("add_expense"))
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.categories.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = state.selectedCategoryId == null,
                        onClick = { stateManager.setCategoryFilter(null) },
                        label = { Text(languageManager.getString("all_expenses")) }
                    )
                }
            }

            if (state.expenses.isEmpty()) {
                Column(modifier = Modifier.fillMaxSize().padding(32.dp)) {
                    Text(
                        languageManager.getString("no_expenses_yet"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.expenses, key = { it.expense.id }) { ewd ->
                        ExpenseCard(expenseWithDetails = ewd, onClick = { onEditExpense(ewd) })
                    }
                }
            }
        }
    }
}
