package com.voxapps.expenses.ui.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import com.voxapps.expenses.data.ExpenseSource
import com.voxapps.expenses.data.dataScore
import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.expenses.domain.llm.DuplicateGroup
import com.voxapps.expenses.state.ExpensesStateManager
import com.voxapps.expenses.ui.LocalLanguageManager
import com.voxapps.expenses.ui.formatAmount
import com.voxapps.ipc.VoxAppsDiscovery
import java.text.DateFormat
import java.util.Date

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
    // Which member of each group is kept, keyed by the pending suggestion's original keepId (stable
    // across recompositions unlike a list index) — defaults to whichever member has the better data
    // per Expense.dataScore (manual edits pinned, then capture-source trust tier, then completeness),
    // not just whatever the detector/AI happened to pick as its anchor. The review UI below still lets
    // the user override this per group.
    var selectedKeepIdByGroup by remember(resolvedGroups) {
        mutableStateOf(resolvedGroups.associate { resolved ->
            val members = listOf(resolved.keep) + resolved.duplicates
            resolved.group.keepId to members.maxBy { it.dataScore() }.id
        })
    }

    // Only these paths actually consult the configured rules (ExpensesRepository.buildDuplicateChecker /
    // findLocalDuplicateGroups / findLocalDuplicateGroupForRow): automatic protection's Local component
    // (Local or Local+AI — both run the local silent-merge/review-only pass), and manual/scheduled check
    // when set to pure Local. Manual/scheduled Local+AI's recall step deliberately does NOT use these
    // rules — it groups candidates by a fixed amount+currency+direction match (ExpensesRepository.
    // duplicateCandidateClusters), casting an intentionally wider net than the precise rules before the
    // AI judges the result, so it's excluded here too.
    val localTuningRelevant = settings.duplicateCheckModeManual == ExpensesSettings.MODE_LOCAL ||
        settings.duplicateCheckModeAutomatic == ExpensesSettings.MODE_LOCAL ||
        settings.duplicateCheckModeAutomatic == ExpensesSettings.MODE_LOCAL_AND_AI

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Plain text header, no enclosing Card — matches every other settings tab's root style
        // (CategoriesSettingsTab, SpendingLimitsSettingsTab, ...); this tab used to wrap its entire
        // content in one large Card, which is why it visually stood out from the rest of Settings as
        // one solid tonal block instead of separate, individually-scoped sections.
        Text(languageManager.getString("expense_cleanup_section_title"), style = MaterialTheme.typography.titleMedium)
        Text(
            languageManager.getString("expense_cleanup_section_desc"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // --- Trigger: automatic (insert-time) protection ---
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(languageManager.getString("duplicate_check_automatic_label"), style = MaterialTheme.typography.labelLarge)
                Text(
                    languageManager.getString("duplicate_check_automatic_desc"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                DuplicateModeChipRow(
                    selected = settings.duplicateCheckModeAutomatic,
                    onSelect = { stateManager.setDuplicateCheckModeAutomatic(it) },
                    languageManager = languageManager,
                    includeOff = true
                )

                if (settings.duplicateCheckModeAutomatic != ExpensesSettings.MODE_OFF) {
                    if (settings.duplicateCheckModeAutomatic == ExpensesSettings.MODE_LOCAL ||
                        settings.duplicateCheckModeAutomatic == ExpensesSettings.MODE_LOCAL_AND_AI
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(languageManager.getString("automatic_protection_review_only_label"), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    languageManager.getString("automatic_protection_review_only_desc"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = settings.automaticProtectionReviewOnly,
                                onCheckedChange = { stateManager.setAutomaticProtectionReviewOnly(it) }
                            )
                        }
                    }

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
                }
            }
        }

        // --- Rules manager — relevant whenever either trigger above includes Local ---
        val subAlpha = if (localTuningRelevant) 1f else 0.4f
        Card(modifier = Modifier.fillMaxWidth().alpha(subAlpha)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(languageManager.getString("near_duplicate_time_window_label"), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

                val duplicateRules by stateManager.duplicateRules.collectAsStateWithLifecycle(initialValue = emptyList())
                DuplicateRulesSection(
                    rules = duplicateRules,
                    globalCombinator = settings.duplicateRuleSetGlobalCombinator,
                    onGlobalCombinatorChange = { stateManager.setDuplicateRuleSetGlobalCombinator(it) },
                    onUpsertRule = { stateManager.upsertDuplicateRule(it) },
                    onDeleteRule = { stateManager.deleteDuplicateRule(it) },
                    onSetRuleEnabled = { id, enabled -> stateManager.setDuplicateRuleEnabled(id, enabled) },
                    languageManager = languageManager
                )
            }
        }

        // --- Manual trigger ---
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
            }
        }

        // --- Schedule — reruns whatever the manual trigger's mode above is set to ---
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(languageManager.getString("scheduled_dedup_label"), style = MaterialTheme.typography.labelLarge)
                Text(
                    languageManager.getString("scheduled_dedup_desc"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            }
        }

        // --- Pending suggestion review ---
        if (resolvedGroups.isNotEmpty()) {
            Text(languageManager.getString("duplicate_expenses_pending_title"), style = MaterialTheme.typography.labelLarge)

            resolvedGroups.forEachIndexed { index, resolved ->
                // All group members in one list so "which one survives" is just which radio
                // button is selected, rather than a fixed keep/duplicates split — the detector's
                // pick (resolved.group.keepId) is only the initial selection, not forced.
                val members = remember(resolved) { listOf(resolved.keep) + resolved.duplicates }
                val selectedId = selectedKeepIdByGroup[resolved.group.keepId] ?: resolved.group.keepId
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
                                members.forEach { member ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth().clickable {
                                            selectedKeepIdByGroup = selectedKeepIdByGroup + (resolved.group.keepId to member.id)
                                        }
                                    ) {
                                        RadioButton(
                                            selected = member.id == selectedId,
                                            onClick = {
                                                selectedKeepIdByGroup = selectedKeepIdByGroup + (resolved.group.keepId to member.id)
                                            }
                                        )
                                        Column {
                                            Text(
                                                languageManager.getString(if (member.id == selectedId) "keep_label" else "duplicate_label"),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (member.id == selectedId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                            )
                                            Text(expensePreview(member, languageManager), style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
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
                        val checked = checkedGroups.mapNotNull { resolvedGroups.getOrNull(it) }
                        val original = checked.map { it.group }
                        val effective = checked.map { resolved ->
                            val memberIds = listOf(resolved.keep.id) + resolved.duplicates.map { it.id }
                            val keepId = selectedKeepIdByGroup[resolved.group.keepId] ?: resolved.group.keepId
                            DuplicateGroup(keepId = keepId, duplicateIds = memberIds.filterNot { it == keepId })
                        }
                        stateManager.approveExpenseDeduplication(original, effective)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DuplicateModeChipRow(
    selected: String,
    onSelect: (String) -> Unit,
    languageManager: com.voxapps.expenses.domain.localization.LanguageManager,
    // Only automatic protection has a meaningful "don't run this at all" state — the manual
    // check/schedule are always explicit user-initiated actions, so Off doesn't apply there.
    includeOff: Boolean = false
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val options = buildList {
            if (includeOff) add(ExpensesSettings.MODE_OFF to "duplicate_mode_off")
            add(ExpensesSettings.MODE_LOCAL to "duplicate_mode_local")
            add(ExpensesSettings.MODE_LOCAL_AND_AI to "duplicate_mode_local_ai")
            add(ExpensesSettings.MODE_AI to "duplicate_mode_ai")
        }
        options.forEach { (mode, labelKey) ->
            FilterChip(
                selected = selected == mode,
                onClick = { onSelect(mode) },
                label = { Text(languageManager.getString(labelKey)) }
            )
        }
    }
}

private fun expensePreview(expense: Expense, languageManager: com.voxapps.expenses.domain.localization.LanguageManager): String {
    val parts = mutableListOf<String>()
    expense.title?.takeIf { it.isNotBlank() }?.let { parts += it }
    parts += formatAmount(expense.totalAmount, expense.currencyCode)
    expense.vendor?.takeIf { it.isNotBlank() }?.let { parts += it }
    expense.bank?.takeIf { it.isNotBlank() }?.let { parts += it }
    parts += DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(expense.dateTime))
    // Same signal Expense.dataScore uses for the keep-picker's default selection — shown here so
    // that default is explainable rather than a black box.
    val sourceKey = when (expense.source) {
        ExpenseSource.MANUAL -> "expense_source_manual"
        ExpenseSource.SCAN -> "expense_source_scan"
        ExpenseSource.NOTIFICATION -> "expense_source_notification"
        ExpenseSource.VOICE -> "expense_source_voice"
    }
    parts += languageManager.getString(sourceKey)
    return parts.joinToString(" · ")
}
