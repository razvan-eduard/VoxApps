package com.voxapps.expenses.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** One thing waiting, how many of it there are, and where it is dealt with. */
data class AttentionItem(
    val labelKey: String,
    val count: Int,
    val onOpen: () -> Unit,
    /** Marks this kind as seen. Nothing is deleted — see [AttentionDismissals]. */
    val onDismiss: () -> Unit
)

/**
 * Everything waiting for the person, in one place.
 *
 * Each of these already existed and each was somewhere else: incomplete records in the list, staged
 * captures three taps into settings, drafted rules further still. A thing that waits where nobody
 * looks is a thing nobody does, so the counts come to the front and the screen they belong to is one
 * tap from the count.
 *
 * Only what has something in it is drawn. A list of zeroes is a list that teaches people it is
 * always empty.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttentionSheet(
    items: List<AttentionItem>,
    onDismiss: () -> Unit
) {
    val languageManager = LocalLanguageManager.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirming by remember { mutableStateOf<AttentionItem?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                languageManager.getString("attention_title"),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            items.filter { it.count > 0 }.forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { item.onOpen(); onDismiss() }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        languageManager.getString(item.labelKey),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        item.count.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    IconButton(onClick = { confirming = item }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = languageManager.getString("attention_dismiss"),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }

    confirming?.let { item ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(languageManager.getString(item.labelKey)) },
            // Says what it does and what it does not: the things themselves stay, and anything new
            // of the same kind will be counted again. Otherwise "dismiss" reads as "delete".
            text = { Text(languageManager.getString("attention_dismiss_confirm")) },
            confirmButton = {
                TextButton(onClick = { item.onDismiss(); confirming = null; onDismiss() }) {
                    Text(languageManager.getString("attention_dismiss"))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) {
                    Text(languageManager.getString("cancel"))
                }
            }
        )
    }
}
