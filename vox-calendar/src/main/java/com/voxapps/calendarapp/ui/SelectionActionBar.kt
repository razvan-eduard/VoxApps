package com.voxapps.calendarapp.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import com.voxapps.design.selection.VoxSelectionBar

/** Replaces [CalendarScreen]'s normal top bar while a Day/Week multi-select is active. */
@Composable
internal fun SelectionActionBar(
    selectedCount: Int,
    onClose: () -> Unit,
    onDelete: () -> Unit,
    onMoveExisting: () -> Unit,
    onMoveNew: () -> Unit
) {
    val languageManager = LocalLanguageManager.current
    VoxSelectionBar(
        count = selectedCount,
        title = { String.format(languageManager.getString("selection_mode_count"), it) },
        onClose = onClose,
        closeContentDescription = languageManager.getString("cancel")
    ) {
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
}
