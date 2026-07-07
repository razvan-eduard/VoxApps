package com.voxapps.notes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.voxapps.notes.R
import com.voxapps.notes.data.Category

@Composable
fun CategorySidebar(
    categories: List<Category>,
    selectedCategoryId: Long?,
    onSelect: (Long?) -> Unit,
    onAddCategory: (String, Long) -> Unit,
    onRemoveCategory: (Category) -> Unit
) {
    var showAdd by remember { mutableStateOf(false) }

    ModalDrawerSheet {
        Text(
            text = stringResource(R.string.categories),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp)
        )

        NavigationDrawerItem(
            label = { Text(stringResource(R.string.all_notes)) },
            selected = selectedCategoryId == null,
            onClick = { onSelect(null) },
            modifier = Modifier.padding(horizontal = 12.dp)
        )

        LazyColumn(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            items(categories, key = { it.id }) { cat ->
                NavigationDrawerItem(
                    label = { Text(cat.name) },
                    selected = selectedCategoryId == cat.id,
                    onClick = { onSelect(cat.id) },
                    icon = {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(CategoryColors.fromStored(cat.colorArgb))
                        )
                    },
                    badge = {
                        IconButton(onClick = { onRemoveCategory(cat) }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.remove_category))
                        }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
        }

        TextButton(
            onClick = { showAdd = true },
            modifier = Modifier.padding(16.dp)
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text(stringResource(R.string.add_category), modifier = Modifier.padding(start = 8.dp))
        }
    }

    if (showAdd) {
        AddCategoryDialog(
            onDismiss = { showAdd = false },
            onConfirm = { name, color ->
                onAddCategory(name, color)
                showAdd = false
            }
        )
    }
}

@Composable
private fun AddCategoryDialog(onDismiss: () -> Unit, onConfirm: (String, Long) -> Unit) {
    var name by remember { mutableStateOf("") }
    var selectedColor by remember { mutableLongStateOf(CategoryColors.toStored(CategoryColors.default)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_category)) },
        text = {
            androidx.compose.foundation.layout.Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.category_name)) },
                    singleLine = true
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CategoryColors.palette.forEach { color ->
                        val stored = CategoryColors.toStored(color)
                        val isSelected = stored == selectedColor
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(color)
                                .then(
                                    if (isSelected)
                                        Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    else Modifier
                                )
                                .clickable { selectedColor = stored }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim(), selectedColor) },
                enabled = name.isNotBlank()
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
