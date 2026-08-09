package com.voxapps.design.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voxapps.design.VoxDarkMode

data class ThemeSettingsStrings(
    val darkModeSectionLabel: String,
    val themeSystemLabel: String,
    val themeLightLabel: String,
    val themeDarkLabel: String,
    val coloredLabel: String,
    val coloredDesc: String
)

/**
 * The shared "Theme" settings page every app renders identically — moved here verbatim from what
 * used to be a duplicated block inside each app's own General settings tab (mirrors [LogsSettingsTab]'s
 * shared-tab convention: pure values-in/callbacks-out, no direct DataStore access, this module has
 * no `LanguageManager` so every label is caller-supplied). [todayEffect], when non-null, adds the
 * "highlight today" section below the divider — only Calendar/Expenses/Notes pass one; the other
 * apps have no "today" card to configure this for. [extraContent], if given, renders below that in
 * the same scrollable column — lets a caller fold app-specific appearance-adjacent settings (e.g.
 * vox-calendar's widget border / animations toggles) into this page instead of duplicating the
 * scroll-column scaffolding to append them elsewhere.
 */
@Composable
fun ThemeSettingsScreen(
    darkMode: VoxDarkMode,
    colored: Boolean,
    onDarkModeChange: (VoxDarkMode) -> Unit,
    onColoredChange: (Boolean) -> Unit,
    strings: ThemeSettingsStrings,
    todayEffect: TodayEffectSettings? = null,
    modifier: Modifier = Modifier,
    extraContent: (@Composable ColumnScope.() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(strings.darkModeSectionLabel, style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val modes = listOf(
                VoxDarkMode.SYSTEM to strings.themeSystemLabel,
                VoxDarkMode.LIGHT to strings.themeLightLabel,
                VoxDarkMode.DARK to strings.themeDarkLabel
            )
            modes.forEach { (mode, label) ->
                FilterChip(
                    selected = darkMode == mode,
                    onClick = { onDarkModeChange(mode) },
                    label = { Text(label) }
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(strings.coloredLabel, style = MaterialTheme.typography.bodyMedium)
                Text(
                    strings.coloredDesc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = colored, onCheckedChange = onColoredChange)
        }

        if (todayEffect != null) {
            HorizontalDivider()
            TodayEffectSettingsCard(todayEffect)
        }

        if (extraContent != null) {
            HorizontalDivider()
            extraContent()
        }
    }
}
