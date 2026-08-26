package com.voxapps.vision.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxapps.apppicker.AppPickerCard
import com.voxapps.apppicker.AppPickerStrings
import com.voxapps.design.settings.SettingsSectionCard
import com.voxapps.textmatch.extract.LineEntities
import com.voxapps.vision.di.VisionContainer
import com.voxapps.vision.domain.liveview.LiveViewApps
import com.voxapps.vision.domain.liveview.LiveViewCategories
import kotlinx.coroutines.launch

/**
 * LiveView's own settings page: the frame toggle, then one card per built-in kind — the same
 * exact/fuzzy switch the duplicate rules carry, with the target app underneath — and the person's
 * own categories, each a name, a pattern of theirs, and the app that receives what it matches.
 */
@Composable
fun LiveViewSettingsPage(container: VisionContainer, modifier: Modifier = Modifier) {
    val languageManager = LocalLanguageManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    val showFrame by container.settingsRepository.liveViewShowFrameFlow.collectAsStateWithLifecycle(initialValue = true)
    val pace by container.settingsRepository.liveViewDetectorPaceFlow.collectAsStateWithLifecycle(
        initialValue = com.voxapps.vision.data.preferences.VisionSettingsRepository.DEFAULT_LIVEVIEW_PACE
    )
    val rescan by container.settingsRepository.liveViewRescanEagernessFlow.collectAsStateWithLifecycle(
        initialValue = com.voxapps.vision.data.preferences.VisionSettingsRepository.DEFAULT_LIVEVIEW_RESCAN
    )
    val style by container.settingsRepository.liveViewResultStyleFlow.collectAsStateWithLifecycle(
        initialValue = com.voxapps.vision.data.preferences.VisionSettingsRepository.DEFAULT_LIVEVIEW_STYLE
    )
    val prefsJson by container.settingsRepository.liveViewCategoryPrefsFlow.collectAsStateWithLifecycle(initialValue = null)
    val customJson by container.settingsRepository.liveViewCustomCategoriesFlow.collectAsStateWithLifecycle(initialValue = null)
    val prefs = remember(prefsJson) { LiveViewCategories.prefsFromJson(prefsJson) }
    val custom = remember(customJson) { LiveViewCategories.customFromJson(customJson) }

    fun savePrefs(kind: LineEntities.Kind, updated: LiveViewCategories.Prefs) {
        scope.launch {
            container.settingsRepository.setLiveViewCategoryPrefs(
                LiveViewCategories.prefsToJson(prefs + (kind to updated))
            )
        }
    }

    fun saveCustom(updated: List<LiveViewCategories.Custom>) {
        scope.launch {
            container.settingsRepository.setLiveViewCustomCategories(LiveViewCategories.customToJson(updated))
        }
    }

    val pickerStrings = AppPickerStrings(
        searchPlaceholder = languageManager.getString("search_apps_placeholder"),
        clear = languageManager.getString("clear"),
        showAllApps = languageManager.getString("show_all_apps"),
        showUserApps = languageManager.getString("show_user_apps"),
        showSystemApps = languageManager.getString("show_system_apps"),
        noAppsFound = languageManager.getString("no_apps_found"),
        expand = languageManager.getString("expand"),
        collapse = languageManager.getString("collapse"),
        noneLabel = languageManager.getString("none_system_default"),
        notSelected = languageManager.getString("none_system_default"),
        noAppsSelected = languageManager.getString("no_apps_selected"),
        defaultAppSummaryFormat = languageManager.getString("default_app_summary"),
        appsSelectedNoDefaultFormat = languageManager.getString("apps_selected_no_default"),
        starredCountSummaryFormat = "%1\$d / %2\$d",
        selected = languageManager.getString("selected"),
        setAsDefault = languageManager.getString("selected"),
        removeDefault = languageManager.getString("clear"),
        done = languageManager.getString("done"),
        cancel = languageManager.getString("cancel")
    )

    var addingCustom by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SettingsSectionCard(languageManager.getString("liveview_frame_section")) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(languageManager.getString("liveview_show_frame_label"), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        languageManager.getString("liveview_show_frame_desc"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = showFrame,
                    onCheckedChange = { scope.launch { container.settingsRepository.setLiveViewShowFrame(it) } }
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(languageManager.getString("liveview_pace_label"), style = MaterialTheme.typography.bodyLarge)
            Text(
                languageManager.getString("liveview_pace_desc"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            com.voxapps.design.picklist.Picklist(
                items = listOf("fast", "balanced", "calm"),
                selected = pace,
                itemLabel = { languageManager.getString("liveview_pace_$it") },
                onSelect = { scope.launch { container.settingsRepository.setLiveViewDetectorPace(it) } },
                modifier = Modifier.padding(top = 8.dp)
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(languageManager.getString("liveview_rescan_label"), style = MaterialTheme.typography.bodyLarge)
            Text(
                languageManager.getString("liveview_rescan_desc"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            com.voxapps.design.picklist.Picklist(
                items = listOf("persistent", "balanced", "eager"),
                selected = rescan,
                itemLabel = { languageManager.getString("liveview_rescan_$it") },
                onSelect = { scope.launch { container.settingsRepository.setLiveViewRescanEagerness(it) } },
                modifier = Modifier.padding(top = 8.dp)
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(languageManager.getString("liveview_style_label"), style = MaterialTheme.typography.bodyLarge)
            Text(
                languageManager.getString("liveview_style_desc"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            com.voxapps.design.picklist.Picklist(
                items = listOf("live", "filled", "frozen"),
                selected = style,
                itemLabel = { languageManager.getString("liveview_style_$it") },
                onSelect = { scope.launch { container.settingsRepository.setLiveViewResultStyle(it) } },
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        SettingsSectionCard(languageManager.getString("liveview_categories_section")) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LiveViewCategories.CONFIGURABLE.forEachIndexed { index, kind ->
                    if (index > 0) HorizontalDivider()
                    val kindPrefs = prefs[kind] ?: LiveViewCategories.Prefs()
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(languageManager.getString(kindTitleKey(kind)), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            languageManager.getString(kindTitleKey(kind) + "_desc"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (LiveViewCategories.fuzzable(kind)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = !kindPrefs.fuzzy,
                                    onClick = { savePrefs(kind, kindPrefs.copy(fuzzy = false)) },
                                    label = { Text(languageManager.getString("liveview_match_exact")) }
                                )
                                FilterChip(
                                    selected = kindPrefs.fuzzy,
                                    onClick = { savePrefs(kind, kindPrefs.copy(fuzzy = true)) },
                                    label = { Text(languageManager.getString("liveview_match_fuzzy")) }
                                )
                            }
                        }
                        Text(
                            languageManager.getString("liveview_baked_label") + ": " +
                                languageManager.getString(bakedLabelKey(kind)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val apps = remember { LiveViewApps.installedApps(context) }
                        AppPickerCard(
                            apps = apps,
                            selectedPackages = kindPrefs.apps,
                            onApply = { savePrefs(kind, kindPrefs.copy(apps = it)) },
                            strings = pickerStrings,
                            label = languageManager.getString("liveview_float_apps")
                        )
                    }
                }
            }
        }

        SettingsSectionCard(languageManager.getString("liveview_custom_title")) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    languageManager.getString("liveview_custom_desc"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (custom.isEmpty()) {
                    Text(
                        languageManager.getString("liveview_custom_empty"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                custom.forEachIndexed { index, category ->
                    if (index > 0) HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(category.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    category.pattern,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { saveCustom(custom - category) }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = languageManager.getString("liveview_custom_delete")
                                )
                            }
                        }
                        val shareApps = remember { LiveViewApps.installedApps(context) }
                        AppPickerCard(
                            apps = shareApps,
                            selectedPackages = category.apps,
                            onApply = { picked ->
                                saveCustom(custom.map {
                                    if (it == category) it.copy(apps = picked) else it
                                })
                            },
                            strings = pickerStrings,
                            label = languageManager.getString("liveview_float_apps")
                        )
                    }
                }
                TextButton(onClick = { addingCustom = true }) {
                    Text(languageManager.getString("liveview_custom_add"))
                }
            }
        }
    }

    if (addingCustom) {
        AddCustomCategoryDialog(
            onDismiss = { addingCustom = false },
            onAdd = { name, pattern ->
                saveCustom(custom + LiveViewCategories.Custom(name, pattern))
                addingCustom = false
            }
        )
    }
}

private fun bakedLabelKey(kind: LineEntities.Kind): String = when (kind) {
    LineEntities.Kind.PHONE -> "liveview_h_call"
    LineEntities.Kind.EMAIL -> "liveview_h_email"
    LineEntities.Kind.URL -> "liveview_h_open"
    LineEntities.Kind.ADDRESS -> "liveview_h_maps"
    else -> "liveview_h_search"
}

private fun kindTitleKey(kind: LineEntities.Kind): String = when (kind) {
    LineEntities.Kind.PHONE -> "liveview_kind_phone"
    LineEntities.Kind.EMAIL -> "liveview_kind_email"
    LineEntities.Kind.URL -> "liveview_kind_url"
    LineEntities.Kind.ADDRESS -> "liveview_kind_address"
    else -> "liveview_kind_generic"
}

/** Name + pattern, and the pattern must compile before it can be saved — a broken regex refused
 *  here is a category that never silently matches nothing. */
@Composable
private fun AddCustomCategoryDialog(onDismiss: () -> Unit, onAdd: (name: String, pattern: String) -> Unit) {
    val languageManager = LocalLanguageManager.current
    var name by remember { mutableStateOf("") }
    var pattern by remember { mutableStateOf("") }
    var patternInvalid by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(languageManager.getString("liveview_custom_add")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(languageManager.getString("liveview_custom_name_label")) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it; patternInvalid = false },
                    label = { Text(languageManager.getString("liveview_custom_pattern_label")) },
                    singleLine = true,
                    isError = patternInvalid
                )
                if (patternInvalid) {
                    Text(
                        languageManager.getString("liveview_custom_pattern_invalid"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && pattern.isNotBlank(),
                onClick = {
                    if (runCatching { Regex(pattern) }.isFailure) {
                        patternInvalid = true
                    } else {
                        onAdd(name.trim(), pattern)
                    }
                }
            ) { Text(languageManager.getString("done")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(languageManager.getString("cancel")) }
        }
    )
}
