package com.voxapps.calendarapp.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable

/** Replaces [CalendarScreen]'s normal top bar while a Day/Week multi-select is active. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectionActionBar(
    selectedCount: Int,
    onClose: () -> Unit,
    onDelete: () -> Unit,
    onMoveExisting: () -> Unit,
    onMoveNew: () -> Unit
) {
    val languageManager = LocalLanguageManager.current
    TopAppBar(
        title = { Text(String.format(languageManager.getString("selection_mode_count"), selectedCount)) },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = languageManager.getString("cancel"))
            }
        },
        actions = {
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = languageManager.getString("selection_delete"))
            }
            IconButton(onClick = onMoveExisting) {
                Icon(Icons.Filled.DriveFileMove, contentDescription = languageManager.getString("selection_move_existing"))
            }
            IconButton(onClick = onMoveNew) {
                Icon(Icons.Filled.CreateNewFolder, contentDescription = languageManager.getString("selection_move_new"))
            }
        }
    )
}
