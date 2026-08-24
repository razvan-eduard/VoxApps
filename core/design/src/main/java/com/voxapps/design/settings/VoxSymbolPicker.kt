package com.voxapps.design.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * A short symbol chosen from a few — a comparison, in practice.
 *
 * Symbols and not words: `>` needs no translating, reads at a glance next to the value it governs,
 * and takes the width a row beside a text field can spare. Which symbols exist is the caller's, so
 * this composable never has to know what any of them mean.
 */
@Composable
fun VoxSymbolPicker(
    current: String,
    options: List<String>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        TextButton(onClick = { open = true }, modifier = Modifier.widthIn(min = 44.dp)) {
            Text(
                current,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { symbol ->
                DropdownMenuItem(
                    text = {
                        Text(
                            symbol,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (symbol == current) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    onClick = { onPick(symbol); open = false }
                )
            }
        }
    }
}
