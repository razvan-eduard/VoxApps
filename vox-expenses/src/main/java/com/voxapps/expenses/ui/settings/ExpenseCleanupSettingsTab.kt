package com.voxapps.expenses.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxapps.design.rememberRequirementGate
import com.voxapps.expenses.data.Expense
import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.expenses.domain.llm.DuplicateGroup
import com.voxapps.expenses.state.ExpensesStateManager
import com.voxapps.expenses.ui.LocalLanguageManager
import com.voxapps.expenses.ui.formatAmount
import com.voxapps.ipc.VoxAppsDiscovery

private data class ResolvedGroup(val group: DuplicateGroup, val keep: Expense, val duplicates: List<Expense>)

/**
 * Expense cleanup: the manual "Find duplicate expenses" trigger, the scheduled-interval control, and
 * a review section for the LLM's pending suggestion — mirrors vox-notes' NoteCleanupSettingsTab
 * exactly in shape. Real financial records aren't cheaply reversible, so nothing is deleted until the
 * user explicitly approves specific groups here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseCleanupSettingsTab(
    settings: ExpensesSettings,
    expenses: List<Expense>,
    stateManager: ExpensesStateManager,
    modifier: Modifier = Modifier
) {
    val languageManager = LocalLanguageManager.current
    val context = LocalContext.current
    val pendingGroups by stateManager.pendingExpenseDuplicateGroups.collectAsStateWithLifecycle(initialValue = emptyList())
    val commanderInstalled = remember { VoxAppsDiscovery.isCommanderInstalled(context) }
    val findDuplicatesGate = rememberRequirementGate(
        satisfied = commanderInstalled,
        requiredMessage = languageManager.getString("commander_required_message")
    ) {
        stateManager.requestExpenseDeduplication(context)
        Toast.makeText(context, languageManager.getString("find_duplicate_expenses_sent_toast"), Toast.LENGTH_SHORT).show()
    }

    // Resolved against the *current* expenses — a group shrinks or disappears if an expense it
    // referenced was edited/deleted since the suggestion arrived, rather than showing stale content.
    val resolvedGroups = remember(pendingGroups, expenses) {
        val byId = expenses.associateBy { it.id }
        pendingGroups.mapNotNull { group ->
            val keepExpense = byId[group.keepId] ?: return@mapNotNull null
            val duplicateExpenses = group.duplicateIds.mapNotNull { byId[it] }
            if (duplicateExpenses.isEmpty()) null else ResolvedGroup(group, keepExpense, duplicateExpenses)
        }
    }

    var checkedGroups by remember(resolvedGroups) { mutableStateOf(resolvedGroups.indices.toSet()) }

    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- Find duplicate expenses (manual trigger) ---
        Text(languageManager.getString("find_duplicate_expenses_button"), style = MaterialTheme.typography.labelLarge)
        Text(
            languageManager.getString("find_duplicate_expenses_desc"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (expenses.size < 2) {
            Text(
                languageManager.getString("find_duplicate_expenses_need_two"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        } else {
            Button(
                onClick = findDuplicatesGate.onClick,
                modifier = Modifier.fillMaxWidth().alpha(findDuplicatesGate.alpha)
            ) {
                Text(languageManager.getString("find_duplicate_expenses_button"))
            }
        }

        HorizontalDivider()

        // --- Scheduled expense cleanup ---
        Text(languageManager.getString("scheduled_dedup_label"), style = MaterialTheme.typography.labelLarge)
        Text(
            languageManager.getString("scheduled_dedup_desc"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            val options = listOf(
                ExpensesSettings.INTERVAL_OFF to "scheduled_dedup_off",
                ExpensesSettings.INTERVAL_DAILY to "scheduled_dedup_daily",
                ExpensesSettings.INTERVAL_WEEKLY to "scheduled_dedup_weekly",
                ExpensesSettings.INTERVAL_MONTHLY to "scheduled_dedup_monthly"
            )
            options.forEach { (interval, labelKey) ->
                FilterChip(
                    selected = settings.scheduledExpenseDedupInterval == interval,
                    onClick = { stateManager.setScheduledExpenseDedupInterval(context, interval) },
                    label = { Text(languageManager.getString(labelKey)) }
                )
            }
        }

        // --- Pending suggestion review ---
        if (resolvedGroups.isNotEmpty()) {
            HorizontalDivider()
            Text(languageManager.getString("duplicate_expenses_pending_title"), style = MaterialTheme.typography.labelLarge)

            resolvedGroups.forEachIndexed { index, resolved ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.Top) {
                            Checkbox(
                                checked = index in checkedGroups,
                                onCheckedChange = { checked ->
                                    checkedGroups = if (checked) checkedGroups + index else checkedGroups - index
                                }
                            )
                            Column(modifier = Modifier.padding(top = 12.dp)) {
                                Text(
                                    languageManager.getString("keep_label"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(expensePreview(resolved.keep), style = MaterialTheme.typography.bodyMedium)

                                resolved.duplicates.forEach { dup ->
                                    Text(
                                        languageManager.getString("duplicate_label"),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(top = 8.dp)
                                    )
                                    Text(expensePreview(dup), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { stateManager.dismissExpenseDeduplication() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(languageManager.getString("dismiss_all_button"))
                }
                Button(
                    onClick = {
                        val approved = checkedGroups.mapNotNull { resolvedGroups.getOrNull(it)?.group }
                        stateManager.approveExpenseDeduplication(approved)
                    },
                    enabled = checkedGroups.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(languageManager.getString("apply_selected_button"))
                }
            }
        }
    }
}

private fun expensePreview(expense: Expense): String {
    val label = expense.title?.takeIf { it.isNotBlank() } ?: expense.vendor ?: "—"
    return "$label · ${formatAmount(expense.totalAmount, expense.currencyCode)}"
}
