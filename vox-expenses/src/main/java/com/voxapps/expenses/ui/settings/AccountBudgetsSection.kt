package com.voxapps.expenses.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voxapps.design.picklist.Picklist
import com.voxapps.design.settings.SettingsSectionCard
import com.voxapps.expenses.data.AccountBudget
import com.voxapps.expenses.data.BankAccount
import com.voxapps.expenses.data.Expense
import com.voxapps.expenses.data.SpendingLimit
import com.voxapps.expenses.domain.accounts.BankAccountTree
import com.voxapps.expenses.domain.budget.BudgetMath
import com.voxapps.expenses.domain.localization.LanguageManager
import com.voxapps.expenses.ui.LocalLanguageManager

/**
 * What there is left to spend, per account and per currency.
 *
 * A budget beside the spending limits because they answer neighbouring questions — one is what you
 * meant to spend, the other is what you refuse to exceed — and looking for either in a different
 * screen is how a person ends up believing the app has neither.
 *
 * Nothing here is a running total: what is left is derived from the records every time it is drawn
 * (see [BudgetMath]), so a capture that arrives late or an expense corrected afterwards is simply
 * counted, with no figure anywhere to fall out of step.
 */
@Composable
fun AccountBudgetsSection(
    accounts: List<BankAccount>,
    budgets: List<AccountBudget>,
    expenses: List<Expense>,
    knownCurrencies: List<String>,
    onUpsert: (AccountBudget) -> Unit,
    onDelete: (AccountBudget) -> Unit,
    modifier: Modifier = Modifier
) {
    val languageManager = LocalLanguageManager.current
    // Only the rows money lives in. A card nested under an account spends the account's budget, so
    // it has none of its own to show — see AccountBudget.
    val holders = remember(accounts) { BankAccountTree.rootsOf(accounts) }
    var addingFor by remember { mutableStateOf<Long?>(null) }

    SettingsSectionCard(languageManager.getString("budgets_title"), modifier = modifier) {
        Text(
            languageManager.getString("budgets_desc"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (holders.isEmpty()) {
            Text(
                languageManager.getString("budgets_no_accounts"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
            return@SettingsSectionCard
        }

        holders.forEach { account ->
            val mine = budgets.filter { it.accountId == account.id }
            Text(
                account.displayName(),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 12.dp)
            )
            mine.forEach { budget ->
                BudgetRow(
                    budget = budget,
                    expenses = expenses,
                    languageManager = languageManager,
                    onDelete = { onDelete(budget) },
                    onTopUp = {
                        // Filling a pot starts its window here: what was spent out of the last one
                        // belongs to the last one.
                        onUpsert(
                            budget.copy(
                                startedAt = System.currentTimeMillis(),
                                reconciledAt = null,
                                reconciledRemaining = null
                            )
                        )
                    }
                )
            }
            if (addingFor == account.id) {
                BudgetEditor(
                    // Its own currency first, then whatever else this device deals in — an account
                    // that charges abroad holds more than one, and each carries its own budget.
                    currencies = (listOf(account.currencyCode) + knownCurrencies).filter { it.isNotBlank() }
                        .distinct()
                        .filterNot { code -> mine.any { it.currencyCode.equals(code, ignoreCase = true) } },
                    languageManager = languageManager,
                    onCancel = { addingFor = null },
                    onSave = { currency, amount, mode, period ->
                        onUpsert(
                            AccountBudget(
                                accountId = account.id,
                                currencyCode = currency,
                                amount = amount,
                                mode = mode,
                                period = period
                            )
                        )
                        addingFor = null
                    }
                )
            } else {
                TextButton(onClick = { addingFor = account.id }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        languageManager.getString("budget_add"),
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        }
    }
}

/** One budget: what is left, out of what, and how it renews. */
@Composable
private fun BudgetRow(
    budget: AccountBudget,
    expenses: List<Expense>,
    languageManager: LanguageManager,
    onDelete: () -> Unit,
    onTopUp: () -> Unit
) {
    val opening = BudgetMath.openingBalance(budget)
    val remaining = BudgetMath.remaining(budget, expenses)
    val fraction = if (opening > 0) (remaining / opening).coerceIn(0.0, 1.0).toFloat() else 0f

    Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "%.2f %s".format(remaining, budget.currencyCode),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                // Overspent is a fact worth reading before the number is: the figure goes red rather
                // than the bar simply stopping at empty.
                color = if (remaining < 0) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface
            )
            Text(
                languageManager.getString("budget_of_amount").format("%.2f".format(opening)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(start = 6.dp)
            )
            if (budget.mode == AccountBudget.MODE_POT) {
                TextButton(onClick = onTopUp) {
                    Text(
                        languageManager.getString("budget_topped_up"),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = languageManager.getString("delete"),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        )
        Text(
            when (budget.mode) {
                AccountBudget.MODE_POT -> languageManager.getString("budget_mode_pot")
                else -> languageManager.getString(
                    if (budget.period == SpendingLimit.PERIOD_WEEKLY) "budget_mode_weekly"
                    else "budget_mode_monthly"
                )
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Naming a new one: which currency, how much, and whether a calendar refills it. */
@Composable
private fun BudgetEditor(
    currencies: List<String>,
    languageManager: LanguageManager,
    onCancel: () -> Unit,
    onSave: (currency: String, amount: Double, mode: String, period: String) -> Unit
) {
    var currency by remember { mutableStateOf(currencies.firstOrNull().orEmpty()) }
    var amountText by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(AccountBudget.MODE_PERIOD) }
    var period by remember { mutableStateOf(SpendingLimit.PERIOD_MONTHLY) }
    val amount = amountText.trim().replace(',', '.').toDoubleOrNull()

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Picklist(
            items = currencies,
            selected = currency.takeIf { it.isNotBlank() },
            itemLabel = { it },
            onSelect = { currency = it }
        )
        OutlinedTextField(
            value = amountText,
            onValueChange = { amountText = it },
            label = { Text(languageManager.getString("budget_amount_label")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mode == AccountBudget.MODE_PERIOD && period == SpendingLimit.PERIOD_MONTHLY,
                onClick = { mode = AccountBudget.MODE_PERIOD; period = SpendingLimit.PERIOD_MONTHLY },
                label = { Text(languageManager.getString("budget_mode_monthly")) }
            )
            FilterChip(
                selected = mode == AccountBudget.MODE_PERIOD && period == SpendingLimit.PERIOD_WEEKLY,
                onClick = { mode = AccountBudget.MODE_PERIOD; period = SpendingLimit.PERIOD_WEEKLY },
                label = { Text(languageManager.getString("budget_mode_weekly")) }
            )
            FilterChip(
                selected = mode == AccountBudget.MODE_POT,
                onClick = { mode = AccountBudget.MODE_POT },
                label = { Text(languageManager.getString("budget_mode_pot")) }
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = { onSave(currency, amount ?: 0.0, mode, period) },
                enabled = currency.isNotBlank() && amount != null && amount > 0
            ) { Text(languageManager.getString("save")) }
            TextButton(onClick = onCancel) { Text(languageManager.getString("cancel")) }
        }
    }
}
