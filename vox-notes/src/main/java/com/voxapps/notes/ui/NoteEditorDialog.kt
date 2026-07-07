package com.voxapps.notes.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.voxapps.notes.R
import com.voxapps.notes.data.Category
import com.voxapps.notes.data.Note

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorDialog(
    initial: Note?,
    categories: List<Category>,
    defaultCategoryId: Long?,
    onDismiss: () -> Unit,
    onSave: (title: String?, text: String, categoryId: Long?) -> Unit
) {
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var text by remember { mutableStateOf(initial?.text.orEmpty()) }
    var categoryId by remember { mutableStateOf(initial?.categoryId ?: defaultCategoryId) }
    var expanded by remember { mutableStateOf(false) }

    val categoryLabel = categories.firstOrNull { it.id == categoryId }?.name
        ?: stringResource(R.string.none)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.add_note else R.string.edit_note)) },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.note_title_optional)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.note_text)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                OutlinedButton(
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Text("${stringResource(R.string.category)}: $categoryLabel")
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.none)) },
                        onClick = { categoryId = null; expanded = false }
                    )
                    categories.forEach { cat ->
                        DropdownMenuItem(
                            text = { Text(cat.name) },
                            onClick = { categoryId = cat.id; expanded = false }
                        )
                    }
                }
            }
        },
        confirmButton = {
            val canSave = title.isNotBlank() || text.isNotBlank()
            TextButton(
                onClick = { onSave(title.trim().ifBlank { null }, text.trim(), categoryId) },
                enabled = canSave
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
