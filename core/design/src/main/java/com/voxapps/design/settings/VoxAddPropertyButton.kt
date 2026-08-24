package com.voxapps.design.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The button that grows a rule: it offers only what the rule does not already use, and disappears
 * once nothing is left to add.
 *
 * A rule is written by naming the few fields that matter, so the fields it does not name should not
 * be on screen competing for attention. This is what replaces having them all drawn and greyed.
 *
 * [available] is (id, display text) — `:core:design` resolves no strings.
 */
@Composable
fun VoxAddPropertyButton(
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
