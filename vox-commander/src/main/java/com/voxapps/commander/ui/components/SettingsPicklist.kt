package com.voxapps.commander.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * Choose one of several declared things, with whatever describes the chosen one directly beneath.
 *
 * The engine screens each wrote this out: a collapsed button showing the current selection, a menu
 * of the alternatives, some greyed out with a reason, and — since today — a connection test under
 * the button. Search providers solved the same problem with radio rows instead, so the two screens
 * behaved differently for no reason anyone chose.
 *
 * [below] is where the selection describes itself — the credential field, the connection test. It
 * belongs to the *selection*, not to the rows: a test attached to menu items would fire a request
 * per item every time the menu opened, which for a list of cloud services is a request per service
 * per glance.
 */
@Composable
fun <T> SettingsPicklist(
    items: List<T>,
    selected: T?,
    itemLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    itemEnabled: (T) -> Boolean = { true },
    disabledSuffix: String = "",
    /** A hint on a row that can still be chosen — "needs an API key" and the like. The greyed-out
     *  reason is [disabledSuffix]; this is for what the user can act on by choosing it. */
    itemNote: (T) -> String = { "" },
    below: @Composable () -> Unit = {}
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selected?.let(itemLabel).orEmpty())
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxWidth()
        ) {
            items.forEach { item ->
                val enabled = itemEnabled(item)
                DropdownMenuItem(
                    text = {
                        Text(
                            text = itemLabel(item) + if (enabled) itemNote(item) else disabledSuffix,
                            color = if (enabled) LocalContentColor.current
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    },
                    onClick = {
                        if (enabled) {
                            onSelect(item)
                            expanded = false
                        }
                    },
                    enabled = enabled
                )
            }
        }
    }

    below()
}
