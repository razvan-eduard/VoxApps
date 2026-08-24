package com.voxapps.design.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** One field a trigger can test, named for display. The caller owns the registry — see the app's own
 *  field list, the same way the duplicate engine takes one. */
data class TriggerField(val id: String, val label: String)

/** One test inside a group: a field, the value it must carry, and how exactly.
 *
 *  [op] is a symbol this module carries and never interprets — what "over" means, and which fields
 *  can be asked it, is the app's knowledge. */
data class TriggerCondition(
    val fieldId: String,
    val value: String,
    val fuzz: Int = 0,
    val op: String = "="
)

/** What a trigger's editor needs said, resolved by the caller — `:core:design` has no language
 *  manager, so shared composables take display text and never keys. */
data class TriggerEditorStrings(
    val anyOfLabel: String,
    val addAlternative: String,
    val addCondition: String,
    val removeCondition: String
)

/**
 * A trigger, edited as what it is: alternatives, each a set of things that must all hold.
 *
 * Sum of products — OR between groups, AND inside one. That shape is not a generalisation for its
 * own sake; it is what people write. "Lidl or Carrefour" is two alternatives of one condition each,
 * "Lidl in Cluj" is one alternative of two, and "(Lidl and Cluj) or Carrefour" needs both at once —
 * which a single AND/OR switch on the rule cannot express at all.
 *
 * The same field may appear as often as it needs to. A structure keyed by field could hold only the
 * last mention, which is why asking for two shops was impossible before this.
 *
 * Which fields exist is the caller's: this module has no knowledge of any app's records, exactly as
 * the duplicate-rule engine has none.
 */
@Composable
fun VoxTriggerEditor(
    groups: List<List<TriggerCondition>>,
    fields: List<TriggerField>,
    strings: TriggerEditorStrings,
    onChange: (List<List<TriggerCondition>>) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * One condition's row, rendered by the caller.
     *
     * The whole condition goes in and the whole condition comes back, rather than only its text: how
     * a value is entered, whether the field can be swapped, and what fuzziness means are all things
     * only the app knows — an amount is typed as a figure and stored as cents, a name has levels of
     * near-match, a category is picked from a list. This module would have to know all of that to
     * offer less.
     */
    conditionEditor: @Composable (condition: TriggerCondition, onCondition: (TriggerCondition) -> Unit) -> Unit
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        groups.forEachIndexed { groupIndex, group ->
            // Every alternative after the first is introduced by the word that says what it is, so
            // the structure is legible without a diagram: OR between cards, AND within one.
            if (groupIndex > 0) {
                Text(
                    strings.anyOfLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    group.forEachIndexed { conditionIndex, condition ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                conditionEditor(condition) { updated ->
                                    onChange(groups.replacing(groupIndex, conditionIndex) { updated })
                                }
                            }
                            IconButton(onClick = {
                                // Removing the last condition removes the alternative: an empty
                                // group would be an alternative that fires on nothing, which is not
                                // a thing anybody means to write.
                                onChange(groups.removing(groupIndex, conditionIndex))
                            }) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = strings.removeCondition,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                    VoxAddPropertyButton(
                        label = strings.addCondition,
                        // Any field, as often as it is needed, here as well as between alternatives.
                        // Two tests on one field inside an AND both have to hold, which is a real
                        // thing to write wherever matching can mean "contains". Where it cannot, the
                        // pair simply never fires — a rule that says nothing, not a rule the editor
                        // should have refused to let anyone write.
                        available = fields.map { it.id to it.label },
                        onPick = { fieldId ->
                            onChange(groups.mapIndexed { i, g ->
                                if (i == groupIndex) g + TriggerCondition(fieldId, "") else g
                            })
                        }
                    )
                }
            }
        }

        VoxAddPropertyButton(
            // With nothing written yet there is no alternative to be an alternative to — the first
            // one is simply the condition.
            label = if (groups.isEmpty()) strings.addCondition else strings.addAlternative,
            available = fields.map { it.id to it.label },
            onPick = { fieldId -> onChange(groups + listOf(listOf(TriggerCondition(fieldId, "")))) }
        )
    }
}

private fun List<List<TriggerCondition>>.replacing(
    groupIndex: Int,
    conditionIndex: Int,
    change: (TriggerCondition) -> TriggerCondition
): List<List<TriggerCondition>> = mapIndexed { g, group ->
    if (g != groupIndex) group
    else group.mapIndexed { c, condition -> if (c == conditionIndex) change(condition) else condition }
}

private fun List<List<TriggerCondition>>.removing(
    groupIndex: Int,
    conditionIndex: Int
): List<List<TriggerCondition>> = mapIndexed { g, group ->
    if (g != groupIndex) group else group.filterIndexed { c, _ -> c != conditionIndex }
}.filter { it.isNotEmpty() }
