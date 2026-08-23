package com.voxapps.expenses.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voxapps.expenses.ui.labelled
import com.voxapps.datahygiene.RemapValueKey
import com.voxapps.design.picklist.Picklist
import com.voxapps.design.settings.RuleCardsSection
import com.voxapps.expenses.data.Category
import com.voxapps.expenses.data.ExpenseRemapFields
import com.voxapps.expenses.data.RemapRuleEntity
import com.voxapps.expenses.data.RemapRuleJson
import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.expenses.domain.localization.LanguageManager
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Spacer

/** The green the editor uses for a selected field's border and glow. */
private val SelectedGlow = Color(0xFF4CAF50)

/**
 * The re-map rule manager (see [com.voxapps.datahygiene.RemapEngine]), in the fast-map rules
 * manager's shape: tap a card to edit, reorder with the arrow column, bulk toggle/delete, search.
 * Rules always apply while individually enabled. The learning controls at the top govern only
 * whether repeated field edits draft DISABLED proposals (see
 * [com.voxapps.expenses.data.RemapPatternSighting]) — proposals appear in the same list with a
 * badge and never act until the user enables them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemapRulesSection(
    rules: List<RemapRuleEntity>,
    categories: List<Category>,
    settings: ExpensesSettings,
    onSetProposalsEnabled: (Boolean) -> Unit,
    onSetLearningSpeed: (Int) -> Unit,
    onUpsertRule: (RemapRuleEntity) -> Unit,
    onDeleteRule: (RemapRuleEntity) -> Unit,
    onDeleteAllRules: () -> Unit,
    onToggleAllRules: (Boolean) -> Unit,
    onReorderRules: (List<Long>) -> Unit,
    languageManager: LanguageManager
) {
    var editingRule by remember { mutableStateOf<RemapRuleEntity?>(null) }
    var creatingRule by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // --- Proposal learning: whether repeated edits draft disabled rules ---
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(languageManager.getString("remap_learning_label"), style = MaterialTheme.typography.bodyLarge)
                Text(
                    languageManager.getString("remap_learning_desc"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = settings.remapProposalsEnabled, onCheckedChange = onSetProposalsEnabled)
        }
        val learningAlpha = if (settings.remapProposalsEnabled) 1f else 0.4f
        Column(modifier = Modifier.alpha(learningAlpha), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(languageManager.getString("field_correction_speed_label"), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    ExpensesSettings.CORRECTION_SPEED_INSTANT to "field_correction_speed_instant",
                    ExpensesSettings.CORRECTION_SPEED_FAST to "field_correction_speed_fast",
                    ExpensesSettings.CORRECTION_SPEED_MEDIUM to "field_correction_speed_medium",
                    ExpensesSettings.CORRECTION_SPEED_SLOW to "field_correction_speed_slow"
                ).forEach { (count, labelKey) ->
                    FilterChip(
                        selected = settings.remapLearningSpeed == count,
                        onClick = { onSetLearningSpeed(count) },
                        label = { Text(languageManager.getString(labelKey)) }
                    )
                }
            }
        }

        HorizontalDivider()

        RuleCardsSection(
            title = languageManager.getString("remap_rules_label"),
            description = languageManager.getString("remap_rules_desc"),
            rules = rules,
            ruleName = { it.name.ifBlank { languageManager.getString("remap_rule_default_name") } },
            ruleSummary = { summary(it, categories, languageManager) },
            ruleBadge = { rule ->
                if (rule.origin == RemapRuleEntity.ORIGIN_PROPOSED) {
                    languageManager.getString("remap_rule_proposed_badge")
                } else null
            },
            ruleEnabled = { it.enabled },
            ruleSearchText = { rule ->
                buildString {
                    append(rule.name); append(' ')
                    RemapRuleJson.decode(rule.matchJson).values.forEach { append(it); append(' ') }
                    RemapRuleJson.decode(rule.setJson).forEach { (id, v) ->
                        append(
                            if (id == ExpenseRemapFields.ID_CATEGORY_ID)
                                categories.firstOrNull { it.id == v.toLongOrNull() }?.labelled() ?: v
                            else v
                        )
                        append(' ')
                    }
                }
            },
            onSetEnabled = { rule, enabled -> onUpsertRule(rule.copy(enabled = enabled)) },
            onClickRule = { editingRule = it },
            onDelete = onDeleteRule,
            onReorder = { reordered -> onReorderRules(reordered.map { it.id }) },
            onToggleAll = onToggleAllRules,
            onDeleteAll = onDeleteAllRules,
            addLabel = languageManager.getString("remap_rule_add"),
            onAdd = { creatingRule = true },
            searchPlaceholder = languageManager.getString("rules_search_placeholder"),
            noSearchMatchText = languageManager.getString("rules_no_search_match"),
            deleteAllTitle = languageManager.getString("rules_delete_all_title"),
            deleteAllMessage = languageManager.getString("rules_delete_all_message"),
            confirmLabel = languageManager.getString("delete"),
            cancelLabel = languageManager.getString("cancel"),
            deleteContentDescription = languageManager.getString("duplicate_rule_delete"),
            deleteConfirmTitle = languageManager.getString("rule_delete_confirm_title"),
            deleteConfirmMessageFormat = languageManager.getString("rule_delete_confirm_message")
        )
    }

    val editTarget = editingRule
    if (editTarget != null) {
        RemapRuleEditSheet(
            initial = editTarget,
            categories = categories,
            languageManager = languageManager,
            onDismiss = { editingRule = null },
            onSave = { onUpsertRule(it); editingRule = null }
        )
    }
    if (creatingRule) {
        RemapRuleEditSheet(
            initial = RemapRuleEntity(
                name = "", matchJson = "{}", setJson = "{}",
                origin = RemapRuleEntity.ORIGIN_USER, sortOrder = rules.size, updatedAt = 0L
            ),
            categories = categories,
            languageManager = languageManager,
            onDismiss = { creatingRule = false },
            onSave = { onUpsertRule(it); creatingRule = false }
        )
    }
}

private fun summary(rule: RemapRuleEntity, categories: List<Category>, languageManager: LanguageManager): String {
    val match = RemapRuleJson.decode(rule.matchJson)
    val fuzz = RemapRuleJson.decode(rule.fuzzJson)
    val set = RemapRuleJson.decode(rule.setJson)
    val and = " ${languageManager.getString("duplicate_rules_combinator_and")} "
    val whenPart = match.entries.joinToString(and) { (id, v) ->
        val fuzzMark = fuzz[id]?.toIntOrNull()?.takeIf { it > 0 }?.let { "•".repeat(it) } ?: ""
        "${fieldLabel(id, languageManager)} = \"$v\"$fuzzMark"
    }
    val setPart = set.entries.joinToString(", ") { (id, v) ->
        val display = if (id == ExpenseRemapFields.ID_CATEGORY_ID) {
            categories.firstOrNull { it.id == v.toLongOrNull() }?.labelled() ?: v
        } else v
        "${fieldLabel(id, languageManager)} → \"$display\""
    }
    return "${languageManager.getString("remap_rule_when")} $whenPart  •  $setPart"
}

private fun fieldLabel(fieldId: String, languageManager: LanguageManager): String {
    val labelKey = (ExpenseRemapFields.matchFields.firstOrNull { it.id == fieldId }?.labelKey
        ?: ExpenseRemapFields.setFields(emptyList()).firstOrNull { it.id == fieldId }?.labelKey)
    return labelKey?.let { languageManager.getString(it) } ?: fieldId
}

/** Risk color per fuzziness step: one lit dot = close match (green), two = medium (amber),
 *  three = loose (red) — looser matching means more records rewritten on weaker evidence. */
private val FuzzStepColors = listOf(Color(0xFF4CAF50), Color(0xFFFFA000), Color(0xFFE53935))

private fun fuzzLevelColor(level: Int): Color =
    FuzzStepColors.getOrElse(level - 1) { FuzzStepColors.last() }

/**
 * The three-dot fuzziness selector: taps cycle 0→1→2→3→0. Each dot wears its step's risk color
 * when lit. Every user tap also floats the new level's name up from the dots and fades it — the
 * same glance-feedback idea as the to-do editor's importance star — so the level is never a
 * guess from dot-counting. [levelLabel] resolves the floating text per level.
 */
@Composable
private fun FuzzDots(
    level: Int,
    enabled: Boolean,
    onCycle: () -> Unit,
    levelLabel: (Int) -> String
) {
    var feedbackLevel by remember { mutableStateOf<Int?>(null) }
    var feedbackTick by remember { mutableStateOf(0) }
    val anim = remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(feedbackTick) {
        if (feedbackLevel != null) {
            anim.snapTo(0f)
            anim.animateTo(1f, androidx.compose.animation.core.tween(durationMillis = 900))
            feedbackLevel = null
        }
    }
    Box(contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier
                .alpha(if (enabled) 1f else 0.3f)
                .clickable(enabled = enabled) {
                    onCycle()
                    feedbackLevel = (level + 1) % 4
                    feedbackTick++
                }
                .padding(horizontal = 6.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(3) { i ->
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            color = if (i < level) FuzzStepColors[i]
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                            shape = CircleShape
                        )
                )
            }
        }
        feedbackLevel?.let { fl ->
            val progress = anim.value
            Text(
                text = levelLabel(fl),
                style = MaterialTheme.typography.labelSmall,
                color = (if (fl == 0) MaterialTheme.colorScheme.onSurfaceVariant else fuzzLevelColor(fl))
                    .copy(alpha = (1f - progress).coerceIn(0f, 1f)),
                modifier = Modifier.offset(y = (-14 - 18 * progress).dp)
            )
        }
    }
}

/**
 * One field slot of the editor: collapsed it is a gray outlined box whose label cuts the border,
 * value grayed; tapped, it expands into a live input with a green border and glow. [expanded]
 * content for non-text values (the category picklist) replaces the text field when provided.
 */
/**
 * The button that grows a rule: it offers only what the rule does not already use, and disappears
 * once nothing is left to add.
 *
 * A rule is written by naming the few fields that matter, so the fields it does not name should not
 * be on screen competing for attention. This is what replaces having them all drawn and greyed.
 */
@Composable
private fun AddPropertyButton(
    label: String,
    available: List<Pair<String, String>>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (available.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        TextButton(onClick = { open = true }) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text(label)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            available.forEach { (id, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = { onPick(id); open = false }
                )
            }
        }
    }
}

@Composable
private fun RuleFieldSlot(
    label: String,
    value: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onDeselect: () -> Unit,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    expandedContent: (@Composable () -> Unit)? = null
) {
    if (!selected) {
        Box(modifier = modifier.alpha(if (enabled) 0.55f else 0.3f)) {
            OutlinedTextField(
                value = value,
                onValueChange = {},
                readOnly = true,
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
            // The field itself is read-only; this overlay is the tap target that selects it.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(enabled = enabled) { onSelect() }
            )
        }
    } else {
        val glow = Modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(4.dp), ambientColor = SelectedGlow, spotColor = SelectedGlow)
        if (expandedContent != null) {
            Box(
                modifier = modifier
                    .then(glow)
                    .border(2.dp, SelectedGlow, RoundedCornerShape(4.dp))
                    .padding(4.dp)
            ) {
                Column {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            color = SelectedGlow,
                            modifier = Modifier.weight(1f).padding(start = 8.dp)
                        )
                        IconButton(onClick = onDeselect, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Filled.Clear, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                    expandedContent()
                }
            }
        } else {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(label) },
                singleLine = true,
                modifier = modifier.then(glow),
                trailingIcon = {
                    IconButton(onClick = onDeselect) {
                        Icon(Icons.Filled.Clear, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = SelectedGlow,
                    unfocusedBorderColor = SelectedGlow,
                    focusedLabelColor = SelectedGlow,
                    unfocusedLabelColor = SelectedGlow
                )
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemapRuleEditSheet(
    initial: RemapRuleEntity,
    categories: List<Category>,
    languageManager: LanguageManager,
    onDismiss: () -> Unit,
    onSave: (RemapRuleEntity) -> Unit
) {
    var name by remember { mutableStateOf(initial.name) }
    var matchValues by remember { mutableStateOf(RemapRuleJson.decode(initial.matchJson)) }
    var setValues by remember { mutableStateOf(RemapRuleJson.decode(initial.setJson)) }
    var fuzzLevels by remember {
        mutableStateOf(RemapRuleJson.decode(initial.fuzzJson).mapValues { it.value.toIntOrNull() ?: 0 })
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(languageManager.getString("duplicate_rule_name_label")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            AddPropertyButton(
                label = languageManager.getString("remap_rule_add_property"),
                available = ExpenseRemapFields.matchFields
                    .filterNot { it.id in matchValues }
                    .map { it.id to languageManager.getString(it.labelKey) },
                onPick = { matchValues = matchValues + (it to "") }
            )

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    languageManager.getString("remap_rule_when_fields_label"),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    languageManager.getString("remap_fuzz_column_label"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Only what the rule actually uses. Drawing every field greyed out put the whole
            // vocabulary on screen at once and made a two-field rule look like a form left mostly
            // blank; a rule is easier to read when it shows what it says and nothing else.
            ExpenseRemapFields.matchFields.filter { it.id in matchValues }.forEach { field ->
                val selected = true
                val isAmount = field.id == ExpenseRemapFields.ID_AMOUNT
                // Stored as cents so two spellings of one figure are one trigger; shown as the
                // figure, because nobody thinks in cents. What is typed is kept as typed while the
                // box has focus — canonicalising every keystroke would fight the person mid-number.
                var amountText by remember(field.id) {
                    mutableStateOf(
                        matchValues[field.id]?.toLongOrNull()?.let { c -> "%.2f".format(c / 100.0) }.orEmpty()
                    )
                }
                val triggerText = if (isAmount) amountText else matchValues[field.id].orEmpty()
                // Level demonstration flash: cycling the dots pulses a garbled-but-still-matching
                // variant of the typed trigger over the field, then fades it away — the typed word
                // itself is never touched.
                var flash by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }
                var flashTick by remember { mutableStateOf(0) }
                val flashAnim = remember { androidx.compose.animation.core.Animatable(0f) }
                androidx.compose.runtime.LaunchedEffect(flashTick) {
                    if (flash != null) {
                        flashAnim.snapTo(0f)
                        flashAnim.animateTo(
                            1f,
                            androidx.compose.animation.core.tween(
                                durationMillis = 1800,
                                easing = androidx.compose.animation.core.LinearEasing
                            )
                        )
                        flash = null
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.weight(1f)) {
                        RuleFieldSlot(
                            label = languageManager.getString(field.labelKey),
                            value = triggerText,
                            selected = selected,
                            enabled = true,
                            onSelect = { matchValues = matchValues + (field.id to "") },
                            onDeselect = {
                                matchValues = matchValues - field.id
                                fuzzLevels = fuzzLevels - field.id
                            },
                            onValueChange = { typed ->
                                if (isAmount) {
                                    amountText = typed
                                    matchValues = matchValues + (field.id to
                                        (ExpenseRemapFields.amountKeyOf(typed) ?: ""))
                                } else {
                                    matchValues = matchValues + (field.id to typed)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        flash?.let { (examples, level) ->
                            val progress = flashAnim.value
                            val alpha = when {
                                progress < 0.12f -> progress / 0.12f
                                progress < 0.65f -> 1f
                                else -> (1f - progress) / 0.35f
                            }
                            Column(
                                modifier = Modifier
                                    .matchParentSize()
                                    .alpha(alpha.coerceIn(0f, 1f))
                                    .background(
                                        MaterialTheme.colorScheme.surfaceContainerLow,
                                        RoundedCornerShape(4.dp)
                                    ),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                examples.forEach { example ->
                                    Text(
                                        text = example,
                                        style = if (examples.size > 1) MaterialTheme.typography.bodyMedium
                                        else MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        color = if (level == 0) MaterialTheme.colorScheme.onSurfaceVariant
                                        else fuzzLevelColor(level)
                                    )
                                }
                            }
                        }
                    }
                    // No fuzziness on a figure: 160 and 168 are not a near miss, they are a
                    // different transaction, and a level that forgave one edit would say otherwise.
                    if (!isAmount) FuzzDots(
                        level = fuzzLevels[field.id] ?: 0,
                        // Gray until the trigger box carries text — a level without a word to
                        // fuzz around demonstrates nothing and matches nothing.
                        enabled = selected && triggerText.isNotBlank(),
                        onCycle = {
                            val next = ((fuzzLevels[field.id] ?: 0) + 1) % 4
                            fuzzLevels = fuzzLevels + (field.id to next)
                            flash = com.voxapps.textmatch.FuzzExamples.forLevel(
                                triggerText,
                                next,
                                languageManager.getString("remap_fuzz_contained_example")
                            ) to next
                            flashTick++
                        },
                        levelLabel = { languageManager.getString("remap_fuzz_level_$it") }
                    )
                }
            }

            val hasTrigger = matchValues.isNotEmpty()
            Text(
                languageManager.getString("remap_rule_set_fields_label"),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.alpha(if (hasTrigger) 1f else 0.4f)
            )
            ExpenseRemapFields.setFields(categories).filter { it.id in setValues }.forEach { field ->
                RuleFieldSlot(
                    label = languageManager.getString(field.labelKey),
                    value = if (field.id == ExpenseRemapFields.ID_CATEGORY_ID) {
                        categories.firstOrNull { it.id == setValues[field.id]?.toLongOrNull() }?.labelled() ?: ""
                    } else setValues[field.id] ?: "",
                    selected = field.id in setValues && hasTrigger,
                    enabled = hasTrigger,
                    onSelect = { setValues = setValues + (field.id to "") },
                    onDeselect = { setValues = setValues - field.id },
                    onValueChange = { setValues = setValues + (field.id to it) },
                    expandedContent = if (field.id == ExpenseRemapFields.ID_CATEGORY_ID) {
                        {
                            Picklist(
                                items = categories,
                                selected = categories.firstOrNull { it.id == setValues[field.id]?.toLongOrNull() },
                                itemLabel = { it.labelled() },
                                onSelect = { setValues = setValues + (field.id to it.id.toString()) },
                                noneLabel = languageManager.getString("none"),
                                onNoneSelected = { setValues = setValues + (field.id to "") }
                            )
                        }
                    } else null
                )
            }

            val cleanMatch = matchValues.mapNotNull { (id, v) ->
                RemapValueKey.normalize(v)?.let { id to it }
            }.toMap()
            val cleanSet = if (hasTrigger) setValues.filterValues { it.isNotBlank() } else emptyMap()
            if (cleanMatch.isEmpty() || cleanSet.isEmpty()) {
                Text(
                    languageManager.getString("remap_rule_needs_both_warning"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            HorizontalDivider()
            AddPropertyButton(
                label = languageManager.getString("remap_rule_add_property"),
                available = ExpenseRemapFields.setFields(categories)
                    .filterNot { it.id in setValues }
                    .map { it.id to languageManager.getString(it.labelKey) },
                onPick = { setValues = setValues + (it to "") }
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(languageManager.getString("cancel"))
                }
                Button(
                    onClick = {
                        val cleanFuzz = fuzzLevels
                            .filterKeys { it in cleanMatch }
                            .filterValues { it > 0 }
                            .mapValues { it.value.toString() }
                        onSave(
                            initial.copy(
                                name = name.trim().ifEmpty { languageManager.getString("remap_rule_default_name") },
                                matchJson = RemapRuleJson.encode(cleanMatch),
                                setJson = RemapRuleJson.encode(cleanSet),
                                fuzzJson = RemapRuleJson.encode(cleanFuzz),
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    },
                    enabled = cleanMatch.isNotEmpty() && cleanSet.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(languageManager.getString("done"))
                }
            }
        }
    }
}
