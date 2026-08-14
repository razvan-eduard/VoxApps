package com.voxapps.design.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The generic "list of user-authored rules" settings section, in the shape Commander's fast-map
 * rules manager established: a search box, a count header with activate-all/deactivate-all and
 * delete-all bulk actions, and one bordered card per rule — index badge, name, summary, optional
 * badge line, move-to-top / move-up reorder column, a compact enabled switch, a delete icon, and
 * the card itself is the edit affordance (tap to edit, no edit icon).
 *
 * What a rule IS — its entity type, its editor, its summary text — belongs to the caller; this
 * owns only the list. Reordering is disabled while a search filter narrows the list, since moving
 * an item "up" inside a filtered view would be ambiguous. Strings arrive resolved: `:core:design`
 * has no LanguageManager, so shared composables take display text, never keys.
 */
@Composable
fun <R> RuleCardsSection(
    title: String,
    description: String,
    rules: List<R>,
    ruleName: (R) -> String,
    ruleSummary: (R) -> String,
    ruleBadge: (R) -> String?,
    ruleEnabled: (R) -> Boolean,
    ruleSearchText: (R) -> String,
    onSetEnabled: (R, Boolean) -> Unit,
    onClickRule: (R) -> Unit,
    onDelete: (R) -> Unit,
    onReorder: (List<R>) -> Unit,
    onToggleAll: (Boolean) -> Unit,
    onDeleteAll: () -> Unit,
    addLabel: String,
    onAdd: () -> Unit,
    searchPlaceholder: String,
    noSearchMatchText: String,
    deleteAllTitle: String,
    deleteAllMessage: String,
    confirmLabel: String,
    cancelLabel: String,
    deleteContentDescription: String
) {
    var searchQuery by remember { mutableStateOf("") }
    var showDeleteAllDialog by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (rules.isNotEmpty()) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(searchPlaceholder, style = MaterialTheme.typography.bodySmall) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(20.dp)) {
                            Icon(Icons.Filled.Clear, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                textStyle = MaterialTheme.typography.bodySmall
            )

            val filtered = if (searchQuery.isBlank()) rules else {
                val q = searchQuery.lowercase()
                rules.filter { ruleSearchText(it).lowercase().contains(q) }
            }
            val isReorderable = searchQuery.isBlank()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (filtered.size < rules.size) "${filtered.size}/${rules.size}" else title,
                    style = MaterialTheme.typography.titleSmall
                )
                Row {
                    val anyActive = rules.any { ruleEnabled(it) }
                    IconButton(onClick = { onToggleAll(!anyActive) }) {
                        Icon(
                            if (anyActive) Icons.Filled.PauseCircle else Icons.Filled.PlayCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(onClick = { showDeleteAllDialog = true }) {
                        Icon(
                            Icons.Filled.DeleteSweep,
                            contentDescription = deleteContentDescription,
                            tint = Color.Red.copy(alpha = 0.7f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            if (filtered.isEmpty()) {
                Text(
                    noSearchMatchText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }

            filtered.forEach { rule ->
                val index = rules.indexOf(rule)
                val enabled = ruleEnabled(rule)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onClickRule(rule) },
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                ) {
                    Row(
                        modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                ruleName(rule),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (enabled) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                            )
                            Text(
                                ruleSummary(rule),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (enabled) MaterialTheme.colorScheme.secondary
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                            )
                            ruleBadge(rule)?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Column {
                            IconButton(
                                onClick = {
                                    val reordered = rules.toMutableList()
                                    reordered.remove(rule)
                                    reordered.add(0, rule)
                                    onReorder(reordered)
                                },
                                enabled = isReorderable && index > 0,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Filled.VerticalAlignTop,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = if (isReorderable && index > 0) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                                )
                            }
                            IconButton(
                                onClick = {
                                    val reordered = rules.toMutableList()
                                    val pos = reordered.indexOf(rule)
                                    if (pos > 0) {
                                        reordered.removeAt(pos)
                                        reordered.add(pos - 1, rule)
                                        onReorder(reordered)
                                    }
                                },
                                enabled = isReorderable && index > 0,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Filled.KeyboardArrowUp,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = if (isReorderable && index > 0) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                                )
                            }
                        }
                        Switch(
                            checked = enabled,
                            onCheckedChange = { onSetEnabled(rule, it) },
                            modifier = Modifier.scale(0.8f)
                        )
                        IconButton(onClick = { onDelete(rule) }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = deleteContentDescription,
                                tint = Color.Red.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }

        OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
            Text(addLabel)
        }
    }

    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            title = { Text(deleteAllTitle) },
            text = { Text(deleteAllMessage) },
            confirmButton = {
                TextButton(onClick = { onDeleteAll(); showDeleteAllDialog = false }) { Text(confirmLabel) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllDialog = false }) { Text(cancelLabel) }
            }
        )
    }
}
