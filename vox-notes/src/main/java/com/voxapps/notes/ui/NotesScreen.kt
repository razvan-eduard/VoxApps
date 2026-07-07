package com.voxapps.notes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voxapps.notes.R
import com.voxapps.notes.data.Note
import com.voxapps.notes.data.NoteWithCategory
import com.voxapps.notes.state.NotesStateManager
import com.voxapps.notes.state.NotesUiState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(
    state: NotesUiState.Unlocked,
    stateManager: NotesStateManager,
    onOpenSettings: () -> Unit
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    var editing by remember { mutableStateOf<EditorTarget?>(null) }
    var showDateSheet by remember { mutableStateOf(false) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            CategorySidebar(
                categories = state.categories,
                selectedCategoryId = state.selectedCategoryId,
                onSelect = {
                    stateManager.setCategoryFilter(it)
                    scope.launch { drawerState.close() }
                },
                onAddCategory = { name, color -> stateManager.addCategory(name, color) },
                onRemoveCategory = { stateManager.removeCategory(it) }
            )
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.notes_title)) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.open_menu))
                        }
                    },
                    actions = {
                        IconButton(onClick = { showDateSheet = true }) {
                            Icon(Icons.Filled.CalendarMonth, contentDescription = stringResource(R.string.sort_and_filter))
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings))
                        }
                    }
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { editing = EditorTarget.New }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_note))
                }
            }
        ) { pad ->
            Column(modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
                FilterChipsRow(state = state, stateManager = stateManager)
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.notes, key = { it.note.id }) { nwc ->
                        NoteCard(
                            item = nwc,
                            onClick = { editing = EditorTarget.Edit(nwc.note) },
                            onDelete = { stateManager.deleteNote(nwc.note) }
                        )
                    }
                }
            }
        }
    }

    editing?.let { target ->
        NoteEditorDialog(
            initial = (target as? EditorTarget.Edit)?.note,
            categories = state.categories,
            defaultCategoryId = state.selectedCategoryId,
            onDismiss = { editing = null },
            onSave = { title, text, categoryId ->
                when (target) {
                    EditorTarget.New -> stateManager.addNote(title, text, categoryId)
                    is EditorTarget.Edit -> stateManager.updateNote(
                        target.note.copy(title = title, text = text, categoryId = categoryId)
                    )
                }
                editing = null
            }
        )
    }

    if (showDateSheet) {
        DateSortSheet(
            sort = state.sort,
            dateFrom = state.dateFrom,
            dateTo = state.dateTo,
            onApply = { sort, from, to ->
                stateManager.setSort(sort)
                stateManager.setDateFilter(from, to)
                showDateSheet = false
            },
            onClear = {
                stateManager.clearDateFilter()
                showDateSheet = false
            },
            onDismiss = { showDateSheet = false }
        )
    }
}

private sealed interface EditorTarget {
    data object New : EditorTarget
    data class Edit(val note: Note) : EditorTarget
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterChipsRow(state: NotesUiState.Unlocked, stateManager: NotesStateManager) {
    val selectedName = state.categories.firstOrNull { it.id == state.selectedCategoryId }?.name
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = state.selectedCategoryId == null,
            onClick = { stateManager.setCategoryFilter(null) },
            label = { Text(stringResource(R.string.all_notes)) }
        )
        if (selectedName != null) {
            FilterChip(
                selected = true,
                onClick = { stateManager.setCategoryFilter(null) },
                label = { Text(selectedName) }
            )
        }
        if (state.isDateFilterActive) {
            FilterChip(
                selected = true,
                onClick = { stateManager.clearDateFilter() },
                label = { Text(stringResource(R.string.date_range)) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteCard(item: NoteWithCategory, onClick: () -> Unit, onDelete: () -> Unit) {
    val note = item.note
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            item.category?.let { cat ->
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(CategoryColors.fromStored(cat.colorArgb))
                )
            }
            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                if (!note.title.isNullOrBlank()) {
                    Text(
                        note.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (note.text.isNotBlank()) {
                    Text(
                        note.text,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete))
            }
        }
    }
}
