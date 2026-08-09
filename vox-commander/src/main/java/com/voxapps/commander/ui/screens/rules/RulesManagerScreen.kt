package com.voxapps.commander.ui.screens.rules

import com.voxapps.design.picklist.Picklist
import com.voxapps.design.picklist.PicklistFieldAnchor
import com.voxapps.commander.ui.LocalLanguageManager

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.voxapps.commander.data.local.dao.FastMapDao
import com.voxapps.commander.domain.intent.model.FastMapRule
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.receiver.CommanderExportHandler
import com.voxapps.commander.domain.intent.registry.AppRegistry
import com.voxapps.commander.domain.localization.LanguageManager
import com.voxapps.commander.state.AppStateManager
import com.voxapps.commander.ui.components.AppSelectorDropdown
import com.voxapps.commander.ui.components.VoiceInputTextField
import com.voxapps.commander.utils.RegexGenerator
import com.voxapps.commander.utils.Strings
import com.voxapps.commander.domain.intent.taxonomy.IntentTaxonomy
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlinx.coroutines.launch
import java.io.File

/** Captures exactly the form fields the user directly edits via the trigger/query token
 *  selectors and the mode chips, for dirty-checking against a baseline (see [RulesManagerContent]'s
 *  hasUnsavedRuleChanges). Deliberately excludes the app/intent-picker fields (targetPackage,
 *  selectedIntentIndex) — those are driven partly by an async probe (LaunchedEffect keyed on
 *  selectedTargetPackage) that can race with the synchronous state set when loading an existing
 *  rule for edit, which would make a naive comparison spuriously report "changed" before the
 *  probe even settles. System-command rules (the ones this was reported against) never touch
 *  those fields at all, so excluding them costs nothing for that case. */
private data class RuleFormSnapshot(
    val triggerWords: List<String>,
    val triggerGroups: List<List<String>>,
    val queryWords: List<String>,
    val lazyQuery: Boolean,
    val anyOrder: Boolean,
    val isSystemCommand: Boolean,
    val selectedDomain: String,
    val selectedAction: String,
    val mediaControlType: String
)

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun RulesManagerContent(

    settingsRepo: SettingsRepository,
    appStateManager: AppStateManager,
    fastMapDao: FastMapDao,
    onChangesDetected: (Boolean) -> Unit = {},
    onSaveAvailabilityChanged: (Boolean) -> Unit = {},
    onSaveRequested: (suspend () -> Unit) -> Unit = {}
) {
        val languageManager = LocalLanguageManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hapticFeedback = androidx.compose.ui.platform.LocalHapticFeedback.current

    val rules by fastMapDao.getAllRules().collectAsStateWithLifecycle(initialValue = emptyList())

    // Local mutable copy for drag-to-reorder (updated optimistically, persisted on drag end)
    var localRules by remember { mutableStateOf<List<FastMapRule>>(emptyList()) }
    androidx.compose.runtime.LaunchedEffect(rules) { localRules = rules }

    val lazyListState = rememberLazyListState()
    val reorderableLazyListState = rememberReorderableLazyListState(lazyListState) { from, to ->
        // Use key-based lookup instead of index, because LazyColumn has header items
        // (form card, search bar) before the rules list, making from.index/to.index offset.
        localRules = localRules.toMutableList().apply {
            val fromIndex = indexOfFirst { it.id == from.key }
            val toIndex = indexOfFirst { it.id == to.key }
            if (fromIndex >= 0 && toIndex >= 0) {
                add(toIndex, removeAt(fromIndex))
            }
        }
        hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentFrequentTick)
    }

    // Voice input text (shared between trigger and query)
    var voiceInputText by remember { mutableStateOf("") }

    // Token state — shared tokens from voice input
    var allTokens by remember { mutableStateOf<List<String>>(emptyList()) }
    val triggerSelectedIndices = remember { mutableStateListOf<Int>() }
    val querySelectedIndices = remember { mutableStateListOf<Int>() }
    
    // Additional trigger groups (OR logic). Each group is a mutableStateListOf<Int> of token indices.
    val triggerGroupIndicesList = remember { mutableStateListOf<MutableList<Int>>() }

    // App + Intent selection
    var selectedTargetPackage by remember { mutableStateOf<String?>(null) }
    var selectedIntentIndex by remember { mutableStateOf(-1) }
    var availableIntents by remember { mutableStateOf<List<AppRegistry.KnownIntents.IntentOption>>(emptyList()) }
    var lazyQuery by remember { mutableStateOf(false) }
    var anyOrder by remember { mutableStateOf(false) }

    // Edit state
    var editingRuleId by remember { mutableStateOf<Long?>(null) }

    // Rule type state: false = App Launch, true = System Command
    var isSystemCommand by remember { mutableStateOf(false) }
    var selectedDomain by remember { mutableStateOf(IntentTaxonomy.Domains.SETTINGS) }
    var selectedAction by remember { mutableStateOf(IntentTaxonomy.Actions.VOLUME_UP) }

    // Media control type for audio transport: "active_session", "default_app", "audio_button"
    var mediaControlType by remember { mutableStateOf("active_session") }

    // Form collapse state — collapsed by default
    var isFormExpanded by remember { mutableStateOf(false) }

    fun currentFormSnapshot() = RuleFormSnapshot(
        triggerWords = triggerSelectedIndices.sorted().map { allTokens[it] },
        triggerGroups = triggerGroupIndicesList.map { group -> group.sorted().map { allTokens[it] } }.filter { it.isNotEmpty() },
        queryWords = querySelectedIndices.sorted().map { allTokens[it] },
        lazyQuery = lazyQuery,
        anyOrder = anyOrder,
        isSystemCommand = isSystemCommand,
        selectedDomain = selectedDomain,
        selectedAction = selectedAction,
        mediaControlType = mediaControlType
    )

    // The snapshot the form started from — either a fresh/empty rule, or (when editing) the
    // rule as it was loaded — updated in resetForm() and in the "load rule for edit" handler
    // below. Comparing against this, rather than just checking "is anything selected," is what
    // avoids reporting unsaved changes for a rule you merely opened without touching.
    var baselineSnapshot by remember { mutableStateOf(currentFormSnapshot()) }

    // Reports whether there's an in-progress rule draft worth warning about before the host
    // dismisses this screen (e.g. on swipe-to-dismiss) — gated on isFormExpanded since a
    // collapsed form has nothing visible/in-progress for the user to lose.
    val hasUnsavedRuleChanges = isFormExpanded && currentFormSnapshot() != baselineSnapshot
    LaunchedEffect(hasUnsavedRuleChanges) {
        onChangesDetected(hasUnsavedRuleChanges)
    }

    // Same validity gate as the in-form Save button below — reported up so the host's discard
    // dialog can offer (and correctly enable/disable) a "Save & Close" option instead of forcing
    // a choice between losing the draft and manually finding this screen's own save button.
    val canSaveRule = (triggerSelectedIndices.isNotEmpty() || triggerGroupIndicesList.any { it.isNotEmpty() } || querySelectedIndices.isNotEmpty() || lazyQuery) &&
        (isSystemCommand || selectedTargetPackage != null)
    LaunchedEffect(canSaveRule) {
        onSaveAvailabilityChanged(canSaveRule)
    }

    // Confirmation dialog state
    var ruleToDelete by remember { mutableStateOf<FastMapRule?>(null) }
    var showDeleteAllDialog by remember { mutableStateOf(false) }

    // Import/Export Rules JSON — the standalone, PC-authored-file path (separate from Hub's own
    // whole-device backup/restore, which goes through CommanderExportHandler/VoxCommandReceiver
    // directly and never touches this UI). Reuses the same buildFastMapRulesJson/parseFastMapRules
    // functions so both paths share one schema.
    var pendingImportRules by remember { mutableStateOf<List<FastMapRule>?>(null) }
    var importErrorVisible by remember { mutableStateOf(false) }

    val exportRulesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val json = CommanderExportHandler.buildFastMapRulesJson(fastMapDao.getAllRulesOnce())
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
            }
        }
    }
    val importRulesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            val parsed = text?.let { CommanderExportHandler.parseFastMapRules(it) }
            if (parsed != null) {
                pendingImportRules = parsed
            } else {
                importErrorVisible = true
            }
        }
    }

    // Filter state
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<String?>(null) } // null = All

    // Voice input handler — splits into tokens
    val onVoiceResult: (String) -> Unit = { spokenText ->
        if (spokenText.isNotBlank()) {
            voiceInputText = spokenText
            allTokens = RegexGenerator.splitIntoTokens(spokenText)
            triggerSelectedIndices.clear()
            querySelectedIndices.clear()
            triggerGroupIndicesList.clear()
        }
    }

    val uiState by appStateManager.uiState.collectAsStateWithLifecycle()
    val modelFilterLang = uiState.modelFilterLang
    val voiceProcessor = uiState.voiceProcessor

    // The same question the rest of the app asks, answered in the one place that computes it.
    // This was a third copy, and the only one that looked for a directory whose *name* began with
    // "vosk-model-" — a convention the entry-point resolver replaced, so an imported model stored
    // under any other name read as missing here while every other screen saw it.
    val isDefaultModelOnDevice = uiState.voiceModelReady

    // Update available intents when app changes
    LaunchedEffect(selectedTargetPackage) {
        val pkg = selectedTargetPackage
        if (pkg != null) {
            availableIntents = AppRegistry.KnownIntents.probeSupported(context, pkg)
            selectedIntentIndex = if (availableIntents.isNotEmpty()) 0 else -1
        } else {
            availableIntents = emptyList()
            selectedIntentIndex = -1
        }
    }

    fun resetForm() {
        voiceInputText = ""
        allTokens = emptyList()
        triggerSelectedIndices.clear()
        querySelectedIndices.clear()
        triggerGroupIndicesList.clear()
        selectedTargetPackage = null
        selectedIntentIndex = -1
        availableIntents = emptyList()
        lazyQuery = false
        anyOrder = false
        editingRuleId = null
        isSystemCommand = false
        selectedDomain = IntentTaxonomy.Domains.SETTINGS
        selectedAction = IntentTaxonomy.Actions.VOLUME_UP
        mediaControlType = "active_session"
        isFormExpanded = false
        baselineSnapshot = currentFormSnapshot()
    }

    // Extracted so the host (TopHeaderContainer's discard-confirmation dialog) can also trigger
    // a save via onSaveRequested, instead of only being able to offer "discard" or "keep editing"
    // for an in-progress rule the user forgot to explicitly save before dismissing.
    suspend fun saveCurrentRule() {
        if (!canSaveRule) return
        val triggerWords = triggerSelectedIndices.sorted().map { allTokens[it] }
        val triggerGroups = triggerGroupIndicesList.map { group ->
            group.sorted().map { idx -> allTokens[idx] }
        }.filter { it.isNotEmpty() }
        val queryWords = querySelectedIndices.sorted().map { allTokens[it] }
        val selectedOption = availableIntents.getOrNull(selectedIntentIndex)
        val existingRule = rules.find { it.id == editingRuleId }
        val existingSortOrder = existingRule?.sortOrder
        val rule = FastMapRule(
            id = editingRuleId ?: 0,
            allWords = allTokens,
            triggerWords = triggerWords,
            triggerGroups = triggerGroups,
            queryWords = queryWords,
            targetPackage = if (isSystemCommand) "" else (selectedTargetPackage ?: ""),
            intentAction = if (isSystemCommand) "" else (selectedOption?.action ?: ""),
            uriTemplate = if (isSystemCommand) null else selectedOption?.variant?.uriTemplate,
            lazyQuery = lazyQuery,
            anyOrder = anyOrder,
            sortOrder = existingSortOrder ?: rules.size,
            isActive = existingRule?.isActive ?: true,
            domain = if (isSystemCommand) selectedDomain else "custom",
            action = if (isSystemCommand) selectedAction else "launch",
            mediaControlType = mediaControlType
        )
        fastMapDao.insertRule(rule)
        resetForm()
    }

    LaunchedEffect(Unit) {
        onSaveRequested { saveCurrentRule() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(bottom = 16.dp)
    ) {
        // --- HEADER ---
        Surface(
            modifier = Modifier.fillMaxWidth(),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
            color = MaterialTheme.colorScheme.surface
        ) {
            Text(
                text = languageManager.getString("rules_manager_title"),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(16.dp)
            )
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
            state = lazyListState,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // --- ADD/EDIT RULE FORM (Collapsible) ---
            item {
                val isEditing = editingRuleId != null
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (isEditing) Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            else Modifier
                        ),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isEditing)
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
                        else
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    ),
                    border = if (isEditing)
                        androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                    else null
                ) {
                    // Bordered title header — acts as toggle button
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isFormExpanded = !isFormExpanded },
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                        color = if (isEditing)
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        else
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isEditing) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(end = 8.dp)
                                    ) {
                                        Text(
                                            text = "EDIT",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = if (editingRuleId == null) languageManager.getString("add_new_fast_trigger") else "#${editingRuleId}",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (editingRuleId == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isEditing) {
                                    OutlinedButton(
                                        onClick = { resetForm() },
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                    ) {
                                        Text(languageManager.getString("cancel_edit"), style = MaterialTheme.typography.labelSmall)
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
                                }
                                Icon(
                                    imageVector = if (isFormExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                    contentDescription = if (isFormExpanded) "Collapse" else "Expand",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    // Form content — only visible when expanded
                    if (isFormExpanded) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {

                        // Voice input
                        VoiceInputTextField(
                            value = voiceInputText,
                            onValueChange = { newValue ->
                                voiceInputText = newValue
                                if (newValue.isNotBlank()) {
                                    allTokens = RegexGenerator.splitIntoTokens(newValue)
                                    triggerSelectedIndices.clear()
                                    querySelectedIndices.clear()
                                    triggerGroupIndicesList.clear()
                                } else {
                                    allTokens = emptyList()
                                    triggerSelectedIndices.clear()
                                    querySelectedIndices.clear()
                                    triggerGroupIndicesList.clear()
                                }
                            },
                            label = { Text(languageManager.getString("voice_input_label")) },

                            modelFilterLang = modelFilterLang,
                            voiceProcessor = voiceProcessor,
                            isModelOnDevice = isDefaultModelOnDevice,
                            onVoiceResult = onVoiceResult
                        )

                        // --- DUAL TOKEN SELECTOR ---
                        if (allTokens.isNotEmpty()) {
                            // Primary trigger tokens
                            TokenSelectorSection(
                                title = languageManager.getString("trigger_section_title"),
                                tokens = allTokens,
                                selectedIndices = triggerSelectedIndices,
                                greyedIndices = querySelectedIndices,
                                onToggle = { index ->
                                    if (triggerSelectedIndices.contains(index)) {
                                        triggerSelectedIndices.remove(index)
                                    } else {
                                        triggerSelectedIndices.add(index)
                                    }
                                }

                            )

                            // Additional trigger groups (OR)
                            triggerGroupIndicesList.forEachIndexed { groupIdx, groupIndices ->
                                Spacer(modifier = Modifier.height(4.dp))
                                
                                // OR separator
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.tertiaryContainer,
                                        modifier = Modifier.padding(vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "OR",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                // Trigger group with delete button
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    TokenSelectorSection(
                                        title = "${languageManager.getString("trigger_section_title")} ${groupIdx + 2}",
                                        tokens = allTokens,
                                        selectedIndices = groupIndices,
                                        greyedIndices = querySelectedIndices,
                                        onToggle = { index ->
                                            if (groupIndices.contains(index)) {
                                                groupIndices.remove(index)
                                            } else {
                                                groupIndices.add(index)
                                            }
                                        },

                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = { triggerGroupIndicesList.removeAt(groupIdx) },
                                        modifier = Modifier.padding(top = 4.dp)
                                    ) {
                                        Icon(
                                            Icons.Filled.Clear,
                                            contentDescription = "Remove trigger",
                                            tint = Color.Red.copy(alpha = 0.6f),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }

                            // Add trigger button
                            OutlinedButton(
                                onClick = { triggerGroupIndicesList.add(mutableStateListOf()) },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 6.dp)
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Add Alternative Trigger", style = MaterialTheme.typography.labelSmall)
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Rule mode selector — manual query / auto-extracted query / any-order
                            // trigger matching are mutually exclusive (a rule can only be one of these
                            // three at a time), so this is a 3-way segmented choice rather than two
                            // independent checkboxes — the invalid "auto-extract + any-order" combination
                            // simply isn't representable. See FastMapRule.anyOrder's doc comment for why:
                            // an any-order trigger pattern is built from zero-width lookaheads, and
                            // lazyQuery relies on `.replace()`-ing the matched trigger text out of the
                            // spoken sentence, which only works for a consuming (ordered) pattern.
                            Text(
                                text = languageManager.getString("rule_mode_section_title"),
                                style = MaterialTheme.typography.labelMedium
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = !lazyQuery && !anyOrder,
                                    onClick = {
                                        lazyQuery = false
                                        anyOrder = false
                                    },
                                    label = { Text(languageManager.getString("rule_mode_manual"), style = MaterialTheme.typography.labelSmall) }
                                )
                                FilterChip(
                                    selected = lazyQuery,
                                    onClick = {
                                        lazyQuery = true
                                        anyOrder = false
                                        querySelectedIndices.clear()
                                    },
                                    label = { Text(languageManager.getString("rule_mode_lazy"), style = MaterialTheme.typography.labelSmall) }
                                )
                                FilterChip(
                                    selected = anyOrder,
                                    onClick = {
                                        anyOrder = true
                                        lazyQuery = false
                                    },
                                    label = { Text(languageManager.getString("rule_mode_any_order"), style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                            Text(
                                text = when {
                                    anyOrder -> languageManager.getString("rule_mode_any_order_hint")
                                    lazyQuery -> languageManager.getString("rule_mode_lazy_hint")
                                    else -> languageManager.getString("rule_mode_manual_hint")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            // Query tokens (disabled when auto-extracting)
                            TokenSelectorSection(
                                title = if (lazyQuery) languageManager.getString("query_auto_title") else languageManager.getString("query_manual_title"),
                                tokens = allTokens,
                                selectedIndices = querySelectedIndices,
                                greyedIndices = triggerSelectedIndices,
                                onToggle = { index ->
                                    if (lazyQuery) return@TokenSelectorSection
                                    if (querySelectedIndices.contains(index)) {
                                        querySelectedIndices.remove(index)
                                    } else {
                                        querySelectedIndices.add(index)
                                    }
                                }

                            )
                        }

                        // --- RULE TYPE SELECTOR ---
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = !isSystemCommand,
                                onClick = { isSystemCommand = false },
                                label = { Text("App Launch", style = MaterialTheme.typography.labelSmall) }
                            )
                            FilterChip(
                                selected = isSystemCommand,
                                onClick = { isSystemCommand = true },
                                label = { Text("System Command", style = MaterialTheme.typography.labelSmall) }
                            )
                        }

                        if (isSystemCommand) {
                            // --- SYSTEM COMMAND SELECTORS ---
                            val systemDomains = IntentTaxonomy.Domains.ALL.filter { it != "custom" }
                            val domainActions = IntentTaxonomy.getActionsForDomain(selectedDomain)

                            Picklist(
                                items = systemDomains,
                                selected = selectedDomain,
                                itemLabel = { it.replaceFirstChar { c -> c.uppercase() } },
                                // The action list is the domain's, so a domain change that left the
                                // old action standing would name a pair the taxonomy does not have.
                                onSelect = { domain ->
                                    selectedDomain = domain
                                    selectedAction = IntentTaxonomy.getActionsForDomain(domain).firstOrNull() ?: ""
                                },
                                anchor = { value, onClick -> PicklistFieldAnchor("Domain", value, onClick) }
                            )

                            val actionLabel: (String) -> String =
                                { it.replaceFirstChar { c -> c.uppercase() }.replace("_", " ") }
                            Picklist(
                                items = domainActions,
                                selected = selectedAction,
                                itemLabel = actionLabel,
                                onSelect = { selectedAction = it },
                                anchor = { value, onClick -> PicklistFieldAnchor("Action", value, onClick) }
                            )

                            // --- MEDIA CONTROL TYPE SELECTOR ---
                            // Show only for audio domain transport controls (play/pause/next/prev)
                            val isTransportAction = selectedAction in listOf(
                                IntentTaxonomy.Actions.PLAY,
                                IntentTaxonomy.Actions.PAUSE,
                                IntentTaxonomy.Actions.NEXT,
                                IntentTaxonomy.Actions.PREV
                            )
                            if (selectedDomain == IntentTaxonomy.Domains.AUDIO && isTransportAction) {
                                Text(
                                    text = "Media Control Type",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    FilterChip(
                                        selected = mediaControlType == "active_session",
                                        onClick = { mediaControlType = "active_session" },
                                        label = { Text("Active Session", style = MaterialTheme.typography.labelSmall) }
                                    )
                                    FilterChip(
                                        selected = mediaControlType == "default_app",
                                        onClick = { mediaControlType = "default_app" },
                                        label = { Text("Default App", style = MaterialTheme.typography.labelSmall) }
                                    )
                                    FilterChip(
                                        selected = mediaControlType == "audio_button",
                                        onClick = { mediaControlType = "audio_button" },
                                        label = { Text("Audio Button", style = MaterialTheme.typography.labelSmall) }
                                    )
                                }
                            }
                        } else {
                        // --- APP SELECTOR ---
                        AppSelectorDropdown(
                            selectedPackage = selectedTargetPackage,
                            onAppSelected = { app ->
                                selectedTargetPackage = app?.packageName
                            },
                            domain = null,
                            label = languageManager.getString("target_app_label"),
                            modifier = Modifier.fillMaxWidth(),
                            allowNone = false

                        )

                        // --- INTENT DROPDOWN ---
                        if (availableIntents.isNotEmpty()) {
                            val selectedOption = availableIntents.getOrNull(selectedIntentIndex) ?: availableIntents.first()
                            val intentCaption = languageManager.getString("intent_action_label")

                            Picklist(
                                items = availableIntents,
                                selected = selectedOption,
                                itemLabel = { it.variant.label },
                                onSelect = { option -> selectedIntentIndex = availableIntents.indexOf(option) },
                                anchor = { value, onClick -> PicklistFieldAnchor(intentCaption, value, onClick) }
                            )
                        }
                        }

                        // --- SAVE BUTTON ---
                        Button(
                            onClick = { scope.launch { saveCurrentRule() } },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = canSaveRule
                        ) {
                            Text(if (editingRuleId == null) languageManager.getString("add_rule_button") else languageManager.getString("update_rule"))
                        }
                    }
                    } // end if (isFormExpanded)
                }
            }

            // --- LIST OF EXISTING RULES ---
            if (localRules.isNotEmpty()) {
                val isReorderable = searchQuery.isBlank() && selectedCategory == null
                // Build categories from rules: app names + system domains
                val appCategories = localRules.filter { it.domain == "custom" }
                    .map { it.targetPackage }
                    .distinct()
                    .filter { it.isNotBlank() }
                    .mapNotNull { pkg ->
                        AppRegistry.resolveByPackage(pkg)?.let { it.displayName to pkg }
                    }
                val systemCategories = localRules.filter { it.domain != "custom" }
                    .map { it.domain }
                    .distinct()
                    .map { it.replaceFirstChar { c -> c.uppercase() } to "__domain__:$it" }
                val categories = (appCategories + systemCategories).sortedBy { it.first }

                // Fuzzy filter
                val filteredRules = localRules.filter { rule ->
                    val matchesCategory = selectedCategory == null || run {
                        val cat = selectedCategory ?: ""
                        if (cat.startsWith("__domain__:")) {
                            val dom = cat.removePrefix("__domain__:")
                            rule.domain == dom
                        } else {
                            rule.targetPackage == cat
                        }
                    }
                    val matchesSearch = searchQuery.isBlank() || run {
                        val q = searchQuery.lowercase()
                        rule.triggerWords.any { it.lowercase().contains(q) } ||
                        rule.triggerGroups.any { group -> group.any { it.lowercase().contains(q) } } ||
                        rule.queryWords.any { it.lowercase().contains(q) } ||
                        rule.allWords.any { it.lowercase().contains(q) } ||
                        rule.targetPackage.lowercase().contains(q) ||
                        rule.intentAction.lowercase().contains(q) ||
                        rule.domain.lowercase().contains(q) ||
                        rule.action.lowercase().contains(q) ||
                        (rule.uriTemplate?.lowercase()?.contains(q) == true) ||
                        (AppRegistry.resolveByPackage(rule.targetPackage)?.displayName?.lowercase()?.contains(q) == true)
                    }
                    matchesCategory && matchesSearch
                }

                item {
                    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        // Search bar
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Search rules...", style = MaterialTheme.typography.bodySmall) },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(20.dp)) {
                                        Icon(Icons.Filled.Clear, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            textStyle = MaterialTheme.typography.bodySmall
                        )

                        // Category filter chips
                        if (categories.isNotEmpty()) {
                            LazyRow(
                                modifier = Modifier.padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                item {
                                    FilterChip(
                                        selected = selectedCategory == null,
                                        onClick = { selectedCategory = null },
                                        label = { Text("All", style = MaterialTheme.typography.labelSmall) }
                                    )
                                }
                                items(categories, key = { (_, pkg) -> pkg }) { (displayName, pkg) ->
                                    FilterChip(
                                        selected = selectedCategory == pkg,
                                        onClick = { selectedCategory = if (selectedCategory == pkg) null else pkg },
                                        label = { Text(displayName, style = MaterialTheme.typography.labelSmall) }
                                    )
                                }
                            }
                        }

                        // Header row with bulk actions
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (filteredRules.size < localRules.size) "${filteredRules.size}/${localRules.size}" else languageManager.getString("active_fast_triggers"),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Row {
                                IconButton(onClick = { exportRulesLauncher.launch("fastmap_rules.json") }) {
                                    Icon(
                                        Icons.Filled.FileUpload,
                                        contentDescription = "Export rules to JSON",
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                IconButton(onClick = { importRulesLauncher.launch(arrayOf("application/json")) }) {
                                    Icon(
                                        Icons.Filled.FileDownload,
                                        contentDescription = "Import rules from JSON",
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                val anyActive = localRules.any { it.isActive }
                                IconButton(onClick = {
                                    scope.launch {
                                        if (anyActive) fastMapDao.deactivateAllRules() else fastMapDao.activateAllRules()
                                    }
                                }) {
                                    Icon(
                                        if (anyActive) Icons.Filled.PauseCircle else Icons.Filled.PlayCircle,
                                        contentDescription = if (anyActive) "Deactivate all" else "Activate all",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                IconButton(onClick = {
                                    showDeleteAllDialog = true
                                }) {
                                    Icon(
                                        Icons.Filled.DeleteSweep,
                                        contentDescription = "Delete all",
                                        tint = Color.Red.copy(alpha = 0.7f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                if (filteredRules.isEmpty()) {
                    item {
                        Text(
                            text = "No rules match your search.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    }
                } else {
                itemsIndexed(filteredRules, key = { _, rule -> rule.id }) { index, rule ->
                    ReorderableItem(reorderableLazyListState, key = rule.id) { isDragging ->
                        val elevation by androidx.compose.animation.core.animateDpAsState(
                            if (isDragging) 8.dp else 0.dp,
                            label = "dragElevation"
                        )
                    val originalIndex = localRules.indexOf(rule)
                    RuleItem(
                        rule = rule,
                        index = originalIndex,
                        totalRules = localRules.size,
                        isEditing = editingRuleId == rule.id,
                        modifier = Modifier
                            .longPressDraggableHandle(
                                onDragStarted = {
                                    hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.GestureThresholdActivate)
                                },
                                onDragStopped = {
                                    hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.GestureEnd)
                                    scope.launch {
                                        fastMapDao.reorderRules(localRules.map { it.id })
                                    }
                                }
                            ),
                        shadowElevation = elevation,
                        onClick = {
                            editingRuleId = rule.id
                            allTokens = rule.allWords
                            triggerSelectedIndices.clear()
                            querySelectedIndices.clear()
                            triggerGroupIndicesList.clear()
                            // Reconstruct selected indices from words
                            rule.triggerWords.forEach { word ->
                                val idx = allTokens.indexOf(word)
                                if (idx >= 0) triggerSelectedIndices.add(idx)
                            }
                            // Reconstruct trigger groups
                            rule.triggerGroups.forEach { group ->
                                val groupIndices = mutableStateListOf<Int>()
                                group.forEach { word ->
                                    val idx = allTokens.indexOf(word)
                                    if (idx >= 0) groupIndices.add(idx)
                                }
                                if (groupIndices.isNotEmpty()) triggerGroupIndicesList.add(groupIndices)
                            }
                            rule.queryWords.forEach { word ->
                                val idx = allTokens.indexOf(word)
                                if (idx >= 0) querySelectedIndices.add(idx)
                            }
                            isSystemCommand = rule.domain != "custom"
                            if (isSystemCommand) {
                                selectedDomain = rule.domain
                                selectedAction = rule.action
                                selectedTargetPackage = null
                            } else {
                                selectedTargetPackage = rule.targetPackage.ifBlank { null }
                            }
                            mediaControlType = rule.mediaControlType.ifBlank { "active_session" }
                            isFormExpanded = true
                            lazyQuery = rule.lazyQuery
                            anyOrder = rule.anyOrder
                            // Re-probe to get available intents, then find matching index
                            if (!isSystemCommand && !rule.targetPackage.isNullOrBlank()) {
                                availableIntents = AppRegistry.KnownIntents.probeSupported(context, rule.targetPackage)
                                selectedIntentIndex = availableIntents.indexOfFirst {
                                    it.action == rule.intentAction && it.variant.uriTemplate == rule.uriTemplate
                                }.let { if (it < 0) 0 else it }
                            } else {
                                availableIntents = emptyList()
                                selectedIntentIndex = -1
                            }
                            voiceInputText = rule.allWords.joinToString(" ")
                            baselineSnapshot = currentFormSnapshot()
                        },
                        onDelete = {
                            ruleToDelete = rule
                        },
                        onMoveUp = {
                            scope.launch {
                                val orderedIds = localRules.map { it.id }.toMutableList()
                                val currentPos = orderedIds.indexOf(rule.id)
                                if (currentPos > 0) {
                                    orderedIds.removeAt(currentPos)
                                    orderedIds.add(currentPos - 1, rule.id)
                                    fastMapDao.reorderRules(orderedIds)
                                }
                            }
                        },
                        onMoveToTop = {
                            scope.launch {
                                val orderedIds = localRules.map { it.id }.toMutableList()
                                orderedIds.remove(rule.id)
                                orderedIds.add(0, rule.id)
                                fastMapDao.reorderRules(orderedIds)
                            }
                        },
                        onToggleActive = {
                            scope.launch {
                                fastMapDao.setRuleActive(rule.id, !rule.isActive)
                            }
                        }

                    )
                    }
                }
                }
            }
        }

    }

    // --- CONFIRMATION DIALOGS ---
    ruleToDelete?.let { rule ->
        AlertDialog(
            onDismissRequest = { ruleToDelete = null },
            title = { Text("Delete Rule") },
            text = { Text("Are you sure you want to delete this rule?\n\nTrigger: ${rule.triggerWords.joinToString(" ")}${if (rule.triggerGroups.isNotEmpty()) " OR " + rule.triggerGroups.filter { it.isNotEmpty() }.joinToString(" OR ") { it.joinToString(" ") } else ""}") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        fastMapDao.deleteRule(rule)
                        if (editingRuleId == rule.id) resetForm()
                    }
                    ruleToDelete = null
                }) { Text("Delete", color = Color.Red) }
            },
            dismissButton = {
                TextButton(onClick = { ruleToDelete = null }) { Text("Cancel") }
            }
        )
    }

    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            title = { Text("Delete All Rules") },
            text = { Text("Are you sure you want to delete ALL ${rules.size} rules? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        fastMapDao.deleteAllRules()
                        resetForm()
                    }
                    showDeleteAllDialog = false
                }) { Text("Delete All", color = Color.Red) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllDialog = false }) { Text("Cancel") }
            }
        )
    }

    pendingImportRules?.let { imported ->
        AlertDialog(
            onDismissRequest = { pendingImportRules = null },
            title = { Text("Import ${imported.size} Rules") },
            text = { Text("Add these rules to your existing ${rules.size}, or replace your entire rule set with this file?") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        imported.forEach { fastMapDao.insertRule(it.copy(id = 0)) }
                    }
                    pendingImportRules = null
                }) { Text("Add") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { pendingImportRules = null }) { Text("Cancel") }
                    TextButton(onClick = {
                        scope.launch {
                            fastMapDao.deleteAllRules()
                            imported.forEach { fastMapDao.insertRule(it.copy(id = 0)) }
                        }
                        pendingImportRules = null
                    }) { Text("Replace All", color = Color.Red) }
                }
            }
        )
    }

    if (importErrorVisible) {
        AlertDialog(
            onDismissRequest = { importErrorVisible = false },
            title = { Text("Import Failed") },
            text = { Text("That file isn't a valid FastMap rules export.") },
            confirmButton = {
                TextButton(onClick = { importErrorVisible = false }) { Text("OK") }
            }
        )
    }
}

@Composable
fun TokenSelectorSection(
    title: String,
    tokens: List<String>,
    selectedIndices: List<Int>,
    greyedIndices: List<Int>,
    onToggle: (Int) -> Unit,

    modifier: Modifier = Modifier
) {
        val languageManager = LocalLanguageManager.current
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(8.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                tokens.forEachIndexed { index, token ->
                    val isSelected = selectedIndices.contains(index)
                    val isGreyed = greyedIndices.contains(index)
                    FilterChip(
                        selected = isSelected,
                        onClick = { onToggle(index) },
                        label = { Text(token) },
                        shape = RoundedCornerShape(16.dp),
                        enabled = !isGreyed,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF4CAF50),
                            selectedLabelColor = Color.White,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            disabledLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                    )
                }
            }

            if (selectedIndices.isEmpty()) {
                Text(
                    text = languageManager.getString("tap_words_hint"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
fun RuleItem(
    rule: FastMapRule,
    index: Int,
    totalRules: Int,
    isEditing: Boolean,
    modifier: Modifier = Modifier,
    shadowElevation: androidx.compose.ui.unit.Dp = 0.dp,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveToTop: () -> Unit,
    onToggleActive: () -> Unit
) {
        val languageManager = LocalLanguageManager.current
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() },
        border = androidx.compose.foundation.BorderStroke(
            width = if (isEditing) 2.dp else 1.dp,
            color = if (isEditing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = shadowElevation)
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Index badge
            Text(
                text = "${index + 1}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (rule.isActive) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                val triggerText = if (rule.triggerWords.isNotEmpty()) rule.triggerWords.joinToString(" ") else languageManager.getString("no_trigger")
                val hasGroups = rule.triggerGroups.isNotEmpty()
                val fullTriggerText = if (hasGroups) {
                    val allGroups = buildList {
                        if (rule.triggerWords.isNotEmpty()) add(rule.triggerWords.joinToString(" "))
                        rule.triggerGroups.filter { it.isNotEmpty() }.forEach { add(it.joinToString(" ")) }
                    }
                    allGroups.joinToString(" OR ")
                } else {
                    triggerText
                }
                Text(
                    text = languageManager.getString("trigger_prefix").format(fullTriggerText),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (rule.isActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )

                val detail = buildString {
                    if (rule.domain != "custom") {
                        append("${rule.domain.replaceFirstChar { it.uppercase() }} > ${rule.action.replace("_", " ").replaceFirstChar { it.uppercase() }}")
                    } else {
                        if (rule.queryWords.isNotEmpty()) append(languageManager.getString("query_prefix").format(rule.queryWords.joinToString(" ")))
                        if (rule.targetPackage.isNotBlank()) {
                            if (isNotEmpty()) append(" | ")
                            append(languageManager.getString("app_prefix").format(rule.targetPackage))
                        }
                        if (rule.intentAction.isNotBlank()) append(" | Intent: ${rule.intentAction}")
                    }
                }
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (rule.isActive) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                )
            }

            // Reorder controls
            Column {
                IconButton(
                    onClick = onMoveToTop,
                    enabled = index > 0,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Filled.VerticalAlignTop,
                        contentDescription = "Move to top",
                        modifier = Modifier.size(18.dp),
                        tint = if (index > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                    )
                }
                IconButton(
                    onClick = onMoveUp,
                    enabled = index > 0,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowUp,
                        contentDescription = "Move up",
                        modifier = Modifier.size(18.dp),
                        tint = if (index > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                    )
                }
            }

            // Active toggle
            Switch(
                checked = rule.isActive,
                onCheckedChange = { onToggleActive() },
                modifier = Modifier.scale(0.8f)
            )

            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red.copy(alpha = 0.7f))
            }
        }
    }
}
