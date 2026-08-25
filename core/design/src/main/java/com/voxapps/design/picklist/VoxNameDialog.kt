package com.voxapps.design.picklist

import com.voxapps.datahygiene.NameCasing
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * Name the thing a list does not have yet.
 *
 * The other half of [Picklist]'s action row: the list offers "New shop…", and this is what opens.
 * One box, and it will not accept blank — a list gains nothing from an entry with no name, and the
 * caller has to handle the empty case anyway if the dialog lets it through. What it hands over is
 * trimmed and capitalized word by word, the same as a rename made in the list itself.
 */
@Composable
fun VoxNameDialog(
    title: String,
    label: String,
    saveLabel: String,
    cancelLabel: String,
    onNamed: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var typed by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                label = { Text(label) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { NameCasing.capitalized(typed)?.let(onNamed); onDismiss() },
                enabled = typed.isBlank().not()
            ) { Text(saveLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(cancelLabel) }
        }
    )
}
