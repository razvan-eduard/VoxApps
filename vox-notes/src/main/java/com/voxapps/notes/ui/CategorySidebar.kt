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
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.ui.unit.dp
import com.voxapps.notes.data.Category
import com.voxapps.notes.data.CategoryPalette

@Composable
fun CategorySidebar(
    categories: List<Category>,
    selectedCategoryId: Long?,
    onSelect: (Long?) -> Unit,
    onAddCategory: (String, Long) -> Unit,
    onEditCategory: (Category, String, Long) -> Unit,
    onRemoveCategory: (Category) -> Unit
) {
    val languageManager = LocalLanguageManager.current
    var showAdd by remember { mutableStateOf(false) }
    var editingCategory by remember { mutableStateOf<Category?>(null) }

    ModalDrawerSheet {
        Text(
            text = languageManager.getString("categories"),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp)
        )

        NavigationDrawerItem(
            label = { Text(languageManager.getString("all_notes")) },
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
                        Row {
                            IconButton(onClick = { editingCategory = cat }) {
                                Icon(Icons.Filled.Edit, contentDescription = languageManager.getString("edit_category"))
                            }
                            IconButton(onClick = { onRemoveCategory(cat) }) {
                                Icon(Icons.Filled.Delete, contentDescription = languageManager.getString("remove_category"))
                            }
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
            Text(languageManager.getString("add_category"), modifier = Modifier.padding(start = 8.dp))
        }
    }

    if (showAdd) {
        AddCategoryDialog(
            existingColors = categories.map { it.colorArgb },
            onDismiss = { showAdd = false },
            onConfirm = { name, color ->
                onAddCategory(name, color)
                showAdd = false
            }
        )
    }

    editingCategory?.let { category ->
        EditCategoryDialog(
            category = category,
            onDismiss = { editingCategory = null },
            onConfirm = { name, color ->
                onEditCategory(category, name, color)
                editingCategory = null
            }
        )
    }
}

/** Shared color-swatch picker row used by both the add and edit category dialogs. */
@Composable
private fun ColorSwatchRow(selectedColor: Long, onSelect: (Long) -> Unit) {
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
                    .clickable { onSelect(stored) }
            )
        }
    }
}

@Composable
private fun AddCategoryDialog(existingColors: List<Long>, onDismiss: () -> Unit, onConfirm: (String, Long) -> Unit) {
    val languageManager = LocalLanguageManager.current
    var name by remember { mutableStateOf("") }
    var selectedColor by remember { mutableLongStateOf(CategoryPalette.unusedOrRandomColor(existingColors)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(languageManager.getString("add_category")) },
        text = {
            androidx.compose.foundation.layout.Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(languageManager.getString("category_name")) },
                    singleLine = true
                )
                ColorSwatchRow(selectedColor = selectedColor, onSelect = { selectedColor = it })
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim(), selectedColor) },
                enabled = name.isNotBlank()
            ) { Text(languageManager.getString("save")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(languageManager.getString("cancel")) }
        }
    )
}

@Composable
private fun EditCategoryDialog(category: Category, onDismiss: () -> Unit, onConfirm: (String, Long) -> Unit) {
    val languageManager = LocalLanguageManager.current
    var name by remember { mutableStateOf(category.name) }
    var selectedColor by remember { mutableLongStateOf(category.colorArgb) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(languageManager.getString("edit_category")) },
        text = {
            androidx.compose.foundation.layout.Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(languageManager.getString("category_name")) },
                    singleLine = true
                )
                ColorSwatchRow(selectedColor = selectedColor, onSelect = { selectedColor = it })
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim(), selectedColor) },
                enabled = name.isNotBlank()
            ) { Text(languageManager.getString("save")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(languageManager.getString("cancel")) }
        }
    )
}
