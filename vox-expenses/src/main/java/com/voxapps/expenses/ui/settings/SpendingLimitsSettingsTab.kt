package com.voxapps.expenses.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voxapps.design.picklist.Picklist
import com.voxapps.expenses.data.Category
import com.voxapps.expenses.data.SpendingLimit
import com.voxapps.expenses.state.ExpensesStateManager
import com.voxapps.expenses.ui.labelled
import com.voxapps.expenses.ui.LocalLanguageManager
import com.voxapps.expenses.ui.formatAmount
import com.voxapps.design.settings.SettingsSectionCard

/**
 * Per-category and/or overall spending limits, checked daily by
 * [com.voxapps.expenses.domain.limits.SpendingLimitCheckWorker] (see its doc comment — always
 * scheduled, a no-op when this list is empty). Amounts are always in the home currency (Currency
 * settings tab), since that's what limits are compared against regardless of an individual expense's
 * own currency.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpendingLimitsSettingsTab(
    limits: List<SpendingLimit>,
    categories: List<Category>,
    homeCurrency: String,
    stateManager: ExpensesStateManager,
    modifier: Modifier = Modifier
) {
    val languageManager = LocalLanguageManager.current
    var addingNew by remember { mutableStateOf(false) }
    var newCategoryId by remember { mutableStateOf<Long?>(null) }
    var newAmountText by remember { mutableStateOf("") }
    var newPeriod by remember { mutableStateOf(SpendingLimit.PERIOD_MONTHLY) }
    var categoryMenuExpanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsSectionCard(languageManager.getString("spending_limits_title")) {
            Text(
                languageManager.getString("spending_limits_desc"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            limits.forEach { limit ->
                val categoryName = limit.categoryId?.let { id -> categories.firstOrNull { it.id == id }?.labelled() }
                    ?: languageManager.getString("overall_spending_label")
                val periodLabel = languageManager.getString(
                    if (limit.period == SpendingLimit.PERIOD_WEEKLY) "period_weekly" else "period_monthly"
                )
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(categoryName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${formatAmount(limit.amountHomeCurrency, homeCurrency)} · $periodLabel",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { stateManager.deleteSpendingLimit(limit) }) {
                            Icon(Icons.Filled.Delete, contentDescription = languageManager.getString("delete"))
                        }
                    }
                }
            }

        }

        SettingsSectionCard(languageManager.getString("add_spending_limit")) {
            if (addingNew) {
                Picklist(
                    items = categories,
                    selected = categories.firstOrNull { it.id == newCategoryId },
                    itemLabel = { it.labelled() },
                    onSelect = { newCategoryId = it.id },
                    // A limit with no category is the overall one, so "none" here is a real choice
                    // rather than an empty selection.
                    noneLabel = languageManager.getString("overall_spending_label"),
                    onNoneSelected = { newCategoryId = null }
                )
                OutlinedTextField(
                    value = newAmountText,
                    onValueChange = { newAmountText = it },
                    label = { Text(languageManager.getString("limit_amount_label") + " ($homeCurrency)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    FilterChip(
                        selected = newPeriod == SpendingLimit.PERIOD_WEEKLY,
                        onClick = { newPeriod = SpendingLimit.PERIOD_WEEKLY },
                        label = { Text(languageManager.getString("period_weekly")) }
                    )
                    FilterChip(
                        selected = newPeriod == SpendingLimit.PERIOD_MONTHLY,
                        onClick = { newPeriod = SpendingLimit.PERIOD_MONTHLY },
                        label = { Text(languageManager.getString("period_monthly")) }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val amount = newAmountText.toDoubleOrNull()
                        if (amount != null) {
                            stateManager.addSpendingLimit(newCategoryId, amount, newPeriod)
                        }
                        addingNew = false
                        newCategoryId = null
                        newAmountText = ""
                    }) { Text(languageManager.getString("save")) }
                    TextButton(onClick = { addingNew = false }) { Text(languageManager.getString("cancel")) }
                }
            } else {
                TextButton(onClick = { addingNew = true }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text(languageManager.getString("add_spending_limit"))
                }
            }
        }
    }
}
