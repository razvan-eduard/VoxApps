package com.voxapps.expenses.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voxapps.datahygiene.RuleCombinator
import com.voxapps.design.VoxFullscreenSheet
import com.voxapps.expenses.data.DuplicateRuleEntity
import com.voxapps.expenses.data.ExpenseRuleFields
import com.voxapps.expenses.domain.localization.LanguageManager

/**
 * The "which fields count as a duplicate" rule builder — the email-filter model: any number of named
 * rules (each a set of fields ANDed or ORed together), combined with one global AND/OR across all the
 * enabled rules. Replaces the old fixed "amount+title-or-vendor" hardcoded behavior; the two rules
 * seeded on first install reproduce that exact behavior as a starting point, fully editable/deletable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicateRulesSection(
    rules: List<DuplicateRuleEntity>,
    globalCombinator: String,
    onGlobalCombinatorChange: (String) -> Unit,
    onUpsertRule: (DuplicateRuleEntity) -> Unit,
    onDeleteRule: (DuplicateRuleEntity) -> Unit,
    onSetRuleEnabled: (Long, Boolean) -> Unit,
    languageManager: LanguageManager
) {
    // Only field id/labelKey are used here (comparator logic is irrelevant to the UI) — a throwaway
    // instance is cheap and avoids exposing a separate id-to-label lookup just for display purposes.
    val fields = remember { ExpenseRuleFields(fuzzyMatchEnabled = true, timeWindowMillis = 0L).all }
    var editingRule by remember { mutableStateOf<DuplicateRuleEntity?>(null) }
    var creatingRule by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<DuplicateRuleEntity?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(languageManager.getString("duplicate_rules_label"), style = MaterialTheme.typography.labelLarge)
        Text(
            languageManager.getString("duplicate_rules_desc"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (rules.size > 1) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(languageManager.getString("duplicate_rules_global_combinator_label"), style = MaterialTheme.typography.bodyMedium)
                FilterChip(
                    selected = globalCombinator == RuleCombinator.OR.name,
                    onClick = { onGlobalCombinatorChange(RuleCombinator.OR.name) },
                    label = { Text(languageManager.getString("duplicate_rules_combinator_or")) }
                )
                FilterChip(
                    selected = globalCombinator == RuleCombinator.AND.name,
                    onClick = { onGlobalCombinatorChange(RuleCombinator.AND.name) },
                    label = { Text(languageManager.getString("duplicate_rules_combinator_and")) }
                )
            }
        }

        rules.forEach { rule ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(rule.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            ruleFieldSummary(rule, fields, languageManager),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            languageManager.getString(
                                if (rule.appliesAutomatically) "duplicate_rule_auto_apply_on" else "duplicate_rule_auto_apply_off"
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { editingRule = rule }) {
                        Icon(Icons.Filled.Edit, contentDescription = languageManager.getString("duplicate_rule_edit"))
                    }
                    IconButton(onClick = { pendingDelete = rule }) {
                        Icon(Icons.Filled.Delete, contentDescription = languageManager.getString("duplicate_rule_delete"))
                    }
                    Switch(checked = rule.enabled, onCheckedChange = { onSetRuleEnabled(rule.id, it) })
                }
            }
        }

        OutlinedButton(onClick = { creatingRule = true }, modifier = Modifier.fillMaxWidth()) {
            Text(languageManager.getString("duplicate_rule_add"))
        }
    }

    pendingDelete?.let { rule ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(languageManager.getString("rule_delete_confirm_title")) },
            text = { Text(String.format(languageManager.getString("rule_delete_confirm_message"), rule.name)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    onDeleteRule(rule)
                    pendingDelete = null
                }) { Text(languageManager.getString("delete")) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingDelete = null }) {
                    Text(languageManager.getString("cancel"))
                }
            }
        )
    }

    val editTarget = editingRule
    if (editTarget != null) {
        DuplicateRuleEditSheet(
            initial = editTarget,
            fields = fields,
            languageManager = languageManager,
            onDismiss = { editingRule = null },
            onSave = { onUpsertRule(it); editingRule = null }
        )
    }
    if (creatingRule) {
        DuplicateRuleEditSheet(
            initial = DuplicateRuleEntity(name = "", fieldIds = emptyList(), combinator = RuleCombinator.AND.name, sortOrder = rules.size),
            fields = fields,
            languageManager = languageManager,
            onDismiss = { creatingRule = false },
            onSave = { onUpsertRule(it); creatingRule = false }
        )
    }
}

private fun ruleFieldSummary(rule: DuplicateRuleEntity, fields: List<com.voxapps.datahygiene.RuleField<com.voxapps.expenses.data.Expense>>, languageManager: LanguageManager): String {
    val fieldsById = fields.associateBy { it.id }
    val labels = rule.fieldIds.mapNotNull { fieldsById[it] }.map { languageManager.getString(it.labelKey) }
    val joiner = if (rule.combinator == RuleCombinator.OR.name) {
        " ${languageManager.getString("duplicate_rules_combinator_or")} "
    } else {
        " ${languageManager.getString("duplicate_rules_combinator_and")} "
    }
    return labels.joinToString(joiner)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DuplicateRuleEditSheet(
    initial: DuplicateRuleEntity,
    fields: List<com.voxapps.datahygiene.RuleField<com.voxapps.expenses.data.Expense>>,
    languageManager: LanguageManager,
    onDismiss: () -> Unit,
    onSave: (DuplicateRuleEntity) -> Unit
) {
    var name by remember { mutableStateOf(initial.name) }
    var selectedFieldIds by remember { mutableStateOf(initial.fieldIds.toSet()) }
    var combinator by remember { mutableStateOf(initial.combinator) }
    var appliesAutomatically by remember { mutableStateOf(initial.appliesAutomatically) }
    var fuzzyMatchEnabled by remember { mutableStateOf(initial.fuzzyMatchEnabled) }
    // Same shell as the remap-rule editor: full-height sheet, no handle, dismissed by dragging
    // the form down from the top of its scroll; the button row below stays put.
    VoxFullscreenSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(languageManager.getString("duplicate_rule_name_label")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Text(languageManager.getString("duplicate_rule_fields_label"), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                fields.forEach { field ->
                    FilterChip(
                        selected = field.id in selectedFieldIds,
                        onClick = {
                            selectedFieldIds = if (field.id in selectedFieldIds) selectedFieldIds - field.id else selectedFieldIds + field.id
                        },
                        label = { Text(languageManager.getString(field.labelKey)) }
                    )
                }
            }
            if (selectedFieldIds.isEmpty()) {
                Text(
                    languageManager.getString("duplicate_rule_needs_field_warning"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Text(languageManager.getString("duplicate_rule_combinator_label"), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = combinator == RuleCombinator.AND.name,
                    onClick = { combinator = RuleCombinator.AND.name },
                    label = { Text(languageManager.getString("duplicate_rules_combinator_and")) }
                )
                FilterChip(
                    selected = combinator == RuleCombinator.OR.name,
                    onClick = { combinator = RuleCombinator.OR.name },
                    label = { Text(languageManager.getString("duplicate_rules_combinator_or")) }
                )
            }

            Text(languageManager.getString("duplicate_rule_match_mode_label"), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !fuzzyMatchEnabled,
                    onClick = { fuzzyMatchEnabled = false },
                    label = { Text(languageManager.getString("near_duplicate_match_exact")) }
                )
                FilterChip(
                    selected = fuzzyMatchEnabled,
                    onClick = { fuzzyMatchEnabled = true },
                    label = { Text(languageManager.getString("near_duplicate_match_fuzzy")) }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(languageManager.getString("duplicate_rule_auto_apply_label"), style = MaterialTheme.typography.labelLarge)
                    Text(
                        languageManager.getString("duplicate_rule_auto_apply_desc"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = appliesAutomatically, onCheckedChange = { appliesAutomatically = it })
            }

            HorizontalDivider()
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                Text(languageManager.getString("cancel"))
            }
            Button(
                onClick = {
                    onSave(
                        initial.copy(
                            name = name.trim().ifEmpty { languageManager.getString("duplicate_rule_default_name") },
                            fieldIds = selectedFieldIds.toList(),
                            combinator = combinator,
                            appliesAutomatically = appliesAutomatically,
                            fuzzyMatchEnabled = fuzzyMatchEnabled
                        )
                    )
                },
                enabled = selectedFieldIds.isNotEmpty(),
                modifier = Modifier.weight(1f)
            ) {
                Text(languageManager.getString("done"))
            }
        }
    }
}
