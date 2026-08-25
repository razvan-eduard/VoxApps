package com.voxapps.design.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The second line of a settings-menu entry: one short sentence saying what the page behind the row
 * holds, so the menu answers "where is that setting?" without opening anything.
 *
 * Passed to a ListItem's `supportingContent` slot. It deliberately sits below ListItem's own
 * supporting size, at the bodySmall-on-onSurfaceVariant pairing every settings card in these apps
 * uses under a bodyLarge label: a menu stacks a dozen rows on one screen, and descriptions at the
 * component default read as a second column of titles instead of an aside to them.
 */
@Composable
fun SettingsMenuDescription(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}
