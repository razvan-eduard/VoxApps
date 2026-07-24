package com.voxapps.expenses.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
 * Expense cleanup: one unified duplicate-detection surface — a 3-way engine choice (Local/Local+AI/AI)
 * for both automatic (insert-time) protection and the manual "Check for duplicates now" trigger, plus
 * a review section shared by whichever engine found something. Real financial records aren't cheaply
 * reversible, so nothing is deleted until the user explicitly approves specific groups here.
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
    val manualModeNeedsCommander = settings.duplicateCheckModeManual != ExpensesSettings.MODE_LOCAL
    val checkNowGate = rememberRequirementGate(
        satisfied = !manualModeNeedsCommander || commanderInstalled,
        requiredMessage = languageManager.getString("commander_required_message")
    ) {
        stateManager.requestDuplicateCheck(context)
        val toastKey = if (manualModeNeedsCommander) "find_duplicate_expenses_sent_toast" else "duplicate_check_local_done_toast"
        Toast.makeText(context, languageManager.getString(toastKey), Toast.LENGTH_SHORT).show()
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

    val localTuningRelevant = settings.duplicateCheckModeManual != ExpensesSettings.MODE_AI ||
        settings.duplicateCheckModeAutomatic != ExpensesSettings.MODE_AI

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(languageManager.getString("expense_cleanup_section_title"), style = MaterialTheme.typography.titleMedium)
                Text(
                    languageManager.getString("expense_cleanup_section_desc"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalDivider()

                // --- Automatic (insert-time) protection ---
                Text(languageManager.getString("duplicate_check_automatic_label"), style = MaterialTheme.typography.labelLarge)
                Text(
                    languageManager.getString("duplicate_check_automatic_desc"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                DuplicateModeChipRow(
                    selected = settings.duplicateCheckModeAutomatic,
                    onSelect = { stateManager.setDuplicateCheckModeAutomatic(it) },
                    languageManager = languageManager
                )

                if (settings.duplicateCheckModeAutomatic != ExpensesSettings.MODE_LOCAL) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(languageManager.getString("auto_accept_duplicate_merges_label"), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                languageManager.getString("auto_accept_duplicate_merges_desc"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.autoAcceptDuplicateMerges,
                            onCheckedChange = { stateManager.setAutoAcceptDuplicateMerges(it) }
                        )
                    }
                }

                HorizontalDivider()

                // --- Local engine tuning — relevant whenever either mode above includes Local ---
                val subAlpha = if (localTuningRelevant) 1f else 0.4f
                Column(modifier = Modifier.alpha(subAlpha), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(languageManager.getString("near_duplicate_match_mode_label"), style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !settings.nearDuplicateFuzzyMatchEnabled,
                            onClick = { stateManager.setNearDuplicateFuzzyMatchEnabled(false) },
                            label = { Text(languageManager.getString("near_duplicate_match_exact")) }
                        )
                        FilterChip(
                            selected = settings.nearDuplicateFuzzyMatchEnabled,
                            onClick = { stateManager.setNearDuplicateFuzzyMatchEnabled(true) },
                            label = { Text(languageManager.getString("near_duplicate_match_fuzzy")) }
                        )
                    }

                    Text(languageManager.getString("near_duplicate_time_window_label"), style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val windowOptions = listOf(
                            ExpensesSettings.NEAR_DUP_WINDOW_1M, ExpensesSettings.NEAR_DUP_WINDOW_2M,
                            ExpensesSettings.NEAR_DUP_WINDOW_5M, ExpensesSettings.NEAR_DUP_WINDOW_10M,
                            ExpensesSettings.NEAR_DUP_WINDOW_15M
                        )
                        windowOptions.forEach { minutes ->
                            FilterChip(
                                selected = settings.nearDuplicateTimeWindowMinutes == minutes,
                                onClick = { stateManager.setNearDuplicateTimeWindowMinutes(minutes) },
                                label = { Text(String.format(languageManager.getString("near_duplicate_interval_minutes"), minutes)) }
                            )
                        }
                    }
                }

                HorizontalDivider()

                // --- Manual check ---
                Text(languageManager.getString("duplicate_check_manual_label"), style = MaterialTheme.typography.labelLarge)
                Text(
                    languageManager.getString("duplicate_check_manual_desc"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                DuplicateModeChipRow(
                    selected = settings.duplicateCheckModeManual,
                    onSelect = { stateManager.setDuplicateCheckModeManual(it) },
                    languageManager = languageManager
                )

                if (expenses.size < 2) {
                    Text(
                        languageManager.getString("find_duplicate_expenses_need_two"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Button(
                        onClick = checkNowGate.onClick,
                        modifier = Modifier.fillMaxWidth().alpha(checkNowGate.alpha)
                    ) {
                        Text(languageManager.getString("find_duplicate_expenses_button"))
                    }
                }

                HorizontalDivider()

                // --- Scheduled check — reruns whatever the manual mode above is set to ---
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
                                    Column(modifier = Modifier.padding(top = 12.dp).weight(1f)) {
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
                                    IconButton(onClick = { stateManager.dismissExpenseDuplicateGroup(resolved.group) }) {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = languageManager.getString("dismiss_group_content_description")
                                        )
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
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DuplicateModeChipRow(
    selected: String,
    onSelect: (String) -> Unit,
    languageManager: com.voxapps.expenses.domain.localization.LanguageManager
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val options = listOf(
            ExpensesSettings.MODE_LOCAL to "duplicate_mode_local",
            ExpensesSettings.MODE_LOCAL_AND_AI to "duplicate_mode_local_ai",
            ExpensesSettings.MODE_AI to "duplicate_mode_ai"
        )
        options.forEach { (mode, labelKey) ->
            FilterChip(
                selected = selected == mode,
                onClick = { onSelect(mode) },
                label = { Text(languageManager.getString(labelKey)) }
            )
        }
    }
}

private fun expensePreview(expense: Expense): String {
    val label = expense.title?.takeIf { it.isNotBlank() } ?: expense.vendor ?: "—"
    return "$label · ${formatAmount(expense.totalAmount, expense.currencyCode)}"
}
