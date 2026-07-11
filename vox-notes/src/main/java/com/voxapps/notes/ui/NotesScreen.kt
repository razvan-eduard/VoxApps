package com.voxapps.notes.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.DocumentScanner
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voxapps.design.DoubleBackToExitHandler
import com.voxapps.notes.data.Note
import com.voxapps.notes.data.NoteWithCategory
import com.voxapps.notes.domain.llm.ScanRequestSender
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
    val languageManager = LocalLanguageManager.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    var editing by remember { mutableStateOf<EditBuffer?>(null) }
    var showDateSheet by remember { mutableStateOf(false) }

    // While editing, back closes the inline editor instead of exiting; at rest, back re-arms the
    // standard double-press-to-exit flow. `enabled` keeps the two mutually exclusive regardless of
    // Compose's internal back-callback ordering.
    BackHandler(enabled = editing != null) { editing = null }
    DoubleBackToExitHandler(
        message = languageManager.getString("press_back_again_to_exit"),
        enabled = editing == null
    )

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
                onEditCategory = { category, name, color ->
                    stateManager.updateCategory(category.copy(name = name, colorArgb = color))
                },
                onRemoveCategory = { stateManager.removeCategory(it) }
            )
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(languageManager.getString("notes_title")) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = languageManager.getString("open_menu"))
                        }
                    },
                    actions = {
                        val context = LocalContext.current
                        IconButton(onClick = { ScanRequestSender.send(context) }) {
                            Icon(Icons.Filled.DocumentScanner, contentDescription = languageManager.getString("scan_note"))
                        }
                        IconButton(onClick = { showDateSheet = true }) {
                            Icon(Icons.Filled.CalendarMonth, contentDescription = languageManager.getString("sort_and_filter"))
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = languageManager.getString("settings"))
                        }
                    }
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = {
                    commitEdit(editing, stateManager)
                    editing = EditBuffer(id = null, title = "", text = "", categoryId = state.selectedCategoryId)
                }) {
                    Icon(Icons.Filled.Add, contentDescription = languageManager.getString("add_note"))
                }
            }
        ) { pad ->
            Column(modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
                FilterChipsRow(state = state, stateManager = stateManager)
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // New-note draft editor at the top.
                    if (editing?.id == null && editing != null) {
                        item(key = "new-note-editor") {
                            NoteEditorCard(
                                title = editing!!.title,
                                text = editing!!.text,
                                categoryId = editing!!.categoryId,
                                categories = state.categories,
                                onTitleChange = { editing = editing!!.copy(title = it) },
                                onTextChange = { editing = editing!!.copy(text = it) },
                                onCategoryChange = { editing = editing!!.copy(categoryId = it) },
                                onDone = { commitEdit(editing, stateManager); editing = null },
                                onDelete = { editing = null }
                            )
                        }
                    }
                    items(state.notes, key = { it.note.id }) { nwc ->
                        if (editing?.id == nwc.note.id) {
                            NoteEditorCard(
                                title = editing!!.title,
                                text = editing!!.text,
                                categoryId = editing!!.categoryId,
                                categories = state.categories,
                                onTitleChange = { editing = editing!!.copy(title = it) },
                                onTextChange = { editing = editing!!.copy(text = it) },
                                onCategoryChange = { editing = editing!!.copy(categoryId = it) },
                                onDone = { commitEdit(editing, stateManager); editing = null },
                                onDelete = { stateManager.deleteNote(nwc.note); editing = null }
                            )
                        } else {
                            CollapsedNoteCard(
                                item = nwc,
                                onClick = {
                                    commitEdit(editing, stateManager)
                                    editing = EditBuffer(nwc.note.id, nwc.note.title.orEmpty(), nwc.note.text, nwc.note.categoryId)
                                }
                            )
                        }
                    }
                }
            }
        }
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

/** Local edit buffer for the inline note editor. [id] == null means a new (unsaved) note. */
private data class EditBuffer(val id: Long?, val title: String, val text: String, val categoryId: Long?)

/** Persist an edit buffer: create/update when it has content, delete an emptied existing note. */
private fun commitEdit(buf: EditBuffer?, stateManager: NotesStateManager) {
    if (buf == null) return
    val title = buf.title.trim().ifBlank { null }
    val text = buf.text.trim()
    val empty = title == null && text.isEmpty()
    when {
        buf.id == null -> if (!empty) stateManager.addNote(title, text, buf.categoryId)
        empty -> stateManager.deleteNoteById(buf.id)
        else -> stateManager.updateNoteFields(buf.id, title, text, buf.categoryId)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterChipsRow(state: NotesUiState.Unlocked, stateManager: NotesStateManager) {
    val languageManager = LocalLanguageManager.current
    val selectedName = state.categories.firstOrNull { it.id == state.selectedCategoryId }?.name
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = state.selectedCategoryId == null,
            onClick = { stateManager.setCategoryFilter(null) },
            label = { Text(languageManager.getString("all_notes")) }
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
                label = { Text(languageManager.getString("date_range")) }
            )
        }
    }
}

