package com.voxapps.notes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.voxapps.calendar.CalendarView
import com.voxapps.datahygiene.DirtyField
import com.voxapps.datahygiene.RecordSource
import com.voxapps.datahygiene.SaveDecision
import com.voxapps.datahygiene.decideForSave
import com.voxapps.design.DoubleBackToExitHandler
import com.voxapps.design.rememberRequirementGate
import com.voxapps.notes.data.Note
import com.voxapps.notes.data.NoteSanitizer
import com.voxapps.notes.data.NoteWithCategory
import com.voxapps.ipc.VoxAppsDiscovery
import com.voxapps.ipc.VoxIpc
import com.voxapps.notes.domain.llm.ScanRequestSender
import com.voxapps.notes.domain.localization.LanguageManager
import com.voxapps.notes.state.NotesStateManager
import com.voxapps.notes.state.NotesUiState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(
    state: NotesUiState.Unlocked,
    stateManager: NotesStateManager,
    calendarViewEnabled: Boolean,
    language: String,
    onOpenSettings: () -> Unit,
    // Widget "Add"/tap-a-note-to-edit triggers (see NotesRoot's doc comments) — 0 = no pending
    // request. Counters, not plain values, so a repeat of the same request still re-fires.
    quickAddTrigger: Int = 0,
    editNoteId: Long = -1L,
    editNoteTrigger: Int = 0
) {
    val languageManager = LocalLanguageManager.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    var editing by remember { mutableStateOf<EditBuffer?>(null) }
    var showDateSheet by remember { mutableStateOf(false) }
    var pendingDeleteNote by remember { mutableStateOf<Note?>(null) }
    var pendingNoteCleanup by remember { mutableStateOf<PendingNoteCleanup?>(null) }

    LaunchedEffect(quickAddTrigger) {
        if (quickAddTrigger > 0) {
            commitEdit(editing, stateManager)
            editing = EditBuffer(id = null, title = "", text = "", categoryId = state.selectedCategoryId)
        }
    }

    LaunchedEffect(editNoteTrigger) {
        if (editNoteTrigger > 0 && editNoteId >= 0) {
            state.notes.firstOrNull { it.note.id == editNoteId }?.let { nwc ->
                commitEdit(editing, stateManager)
                editing = EditBuffer(nwc.note.id, nwc.note.title.orEmpty(), nwc.note.text, nwc.note.categoryId)
            }
        }
    }

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
                        // Scan needs Vision installed to even launch, and Commander installed for
                        // the OCR-cleanup step that runs after — stays visible but dimmed, with an
                        // explanatory toast on tap naming whichever one is missing, rather than
                        // silently failing (or crashing, for the Vision case).
                        val visionInstalled = remember { VoxAppsDiscovery.isAppInstalled(context, VoxIpc.VISION_PACKAGE) }
                        val commanderInstalled = remember { VoxAppsDiscovery.isCommanderInstalled(context) }
                        val scanGate = rememberRequirementGate(
                            satisfied = visionInstalled && commanderInstalled,
                            requiredMessage = languageManager.getString(
                                if (!visionInstalled) "vision_required_message" else "commander_required_message"
                            )
                        ) { ScanRequestSender.send(context) }
                        IconButton(onClick = scanGate.onClick, modifier = Modifier.alpha(scanGate.alpha)) {
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
                // Hidden while a note is expanded for editing — the expanded card already occupies
                // that same bottom-right area's attention, and tapping it mid-edit would commit
                // whatever's currently open before starting a new draft, which read as a confusing
                // double-action rather than a clear "add note" affordance.
                if (editing == null) {
                    FloatingActionButton(onClick = {
                        commitEdit(editing, stateManager)
                        editing = EditBuffer(id = null, title = "", text = "", categoryId = state.selectedCategoryId)
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = languageManager.getString("add_note"))
                    }
                }
            }
        ) { pad ->
            Column(modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
                FilterChipsRow(state = state, stateManager = stateManager)
                if (calendarViewEnabled) {
                    CalendarView(
                        items = state.notes.map(::NoteCalendarItem),
                        modifier = Modifier.fillMaxSize(),
                        locale = java.util.Locale.forLanguageTag(language),
                        todayContentDescription = languageManager.getString("today"),
                        itemContent = { calItem ->
                            CollapsedNoteCard(
                                item = calItem.nwc,
                                onClick = {
                                    commitEdit(editing, stateManager)
                                    editing = EditBuffer(
                                        calItem.nwc.note.id,
                                        calItem.nwc.note.title.orEmpty(),
                                        calItem.nwc.note.text,
                                        calItem.nwc.note.categoryId
                                    )
                                }
                            )
                        }
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // New-note draft editor at the top.
                        if (editing?.id == null && editing != null) {
                            item(key = "new-note-editor") {
                                NoteEditorCard(
                                    noteId = editing!!.id,
                                    stateManager = stateManager,
                                    title = editing!!.title,
                                    text = editing!!.text,
                                    categoryId = editing!!.categoryId,
                                    categories = state.categories,
                                    onTitleChange = { editing = editing!!.copy(title = it) },
                                    onTextChange = { editing = editing!!.copy(text = it) },
                                    onCategoryChange = { editing = editing!!.copy(categoryId = it) },
                                    onAddCategory = { name, color, onResult -> stateManager.addCategory(name, color, onResult) },
                                    onDone = {
                                        val cleanup = commitEdit(editing, stateManager, confirmCleanup = true)
                                        if (cleanup != null) pendingNoteCleanup = cleanup else editing = null
                                    },
                                    onDelete = { editing = null }
                                )
                            }
                        }
                        items(state.notes, key = { it.note.id }) { nwc ->
                            if (editing?.id == nwc.note.id) {
                                NoteEditorCard(
                                    noteId = editing!!.id,
                                    stateManager = stateManager,
                                    title = editing!!.title,
                                    text = editing!!.text,
                                    categoryId = editing!!.categoryId,
                                    categories = state.categories,
                                    onTitleChange = { editing = editing!!.copy(title = it) },
                                    onTextChange = { editing = editing!!.copy(text = it) },
                                    onCategoryChange = { editing = editing!!.copy(categoryId = it) },
                                    onAddCategory = { name, color, onResult -> stateManager.addCategory(name, color, onResult) },
                                    onDone = {
                                        val cleanup = commitEdit(editing, stateManager, confirmCleanup = true)
                                        if (cleanup != null) pendingNoteCleanup = cleanup else editing = null
                                    },
                                    onDelete = { pendingDeleteNote = nwc.note }
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
    }

    // In calendar view, editing (new or existing note) happens in a bottom sheet instead of an
    // inline LazyColumn swap — the calendar's day cells have no natural "replace this row" slot.
    if (calendarViewEnabled && editing != null) {
        val current = editing!!
        ModalBottomSheet(onDismissRequest = { editing = null }) {
            NoteEditorCard(
                noteId = current.id,
                stateManager = stateManager,
                title = current.title,
                text = current.text,
                categoryId = current.categoryId,
                categories = state.categories,
                onTitleChange = { editing = current.copy(title = it) },
                onTextChange = { editing = current.copy(text = it) },
                onCategoryChange = { editing = current.copy(categoryId = it) },
                onAddCategory = { name, color, onResult -> stateManager.addCategory(name, color, onResult) },
                onDone = {
                    val cleanup = commitEdit(editing, stateManager, confirmCleanup = true)
                    if (cleanup != null) pendingNoteCleanup = cleanup else editing = null
                },
                onDelete = {
                    pendingDeleteNote = state.notes.firstOrNull { it.note.id == current.id }?.note
                }
            )
        }
    }

    pendingNoteCleanup?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingNoteCleanup = null },
            title = { Text(languageManager.getString("cleanup_confirm_title")) },
            text = {
                Column {
                    Text(languageManager.getString("cleanup_confirm_message"))
                    Spacer(Modifier.height(8.dp))
                    pending.dirtyFields.forEach { field ->
                        Text(
                            buildAnnotatedString {
                                append("${noteFieldLabel(languageManager, field.fieldKey)}: ")
                                withStyle(SpanStyle(color = OffenseRed, fontWeight = FontWeight.Bold)) {
                                    append(field.value)
                                }
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        saveNote(pending.id, NoteSanitizer.sanitize(pending.note), stateManager)
                        pendingNoteCleanup = null
                        editing = null
                    }
                ) { Text(languageManager.getString("auto_clean")) }
            },
            dismissButton = {
                TextButton(onClick = { pendingNoteCleanup = null }) { Text(languageManager.getString("cancel")) }
            }
        )
    }

    pendingDeleteNote?.let { note ->
        ConfirmDeleteDialog(
            title = languageManager.getString("delete_note_title"),
            message = languageManager.getString("delete_note_message"),
            onConfirm = {
                stateManager.deleteNote(note)
                pendingDeleteNote = null
                editing = null
            },
            onDismiss = { pendingDeleteNote = null }
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

@Composable
private fun ConfirmDeleteDialog(title: String, message: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val languageManager = LocalLanguageManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(languageManager.getString("delete")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(languageManager.getString("cancel")) }
        }
    )
}

/** Local edit buffer for the inline note editor. [id] == null means a new (unsaved) note. */
private data class EditBuffer(val id: Long?, val title: String, val text: String, val categoryId: Long?)

/** A dirty save the user needs to accept auto-clean or cancel to fix manually. */
private data class PendingNoteCleanup(val id: Long?, val note: Note, val dirtyFields: List<DirtyField>)

private val OffenseRed = Color(0xFFD32F2F)

private fun noteFieldLabel(languageManager: LanguageManager, fieldKey: String): String = when (fieldKey) {
    "title" -> languageManager.getString("note_title_optional")
    else -> fieldKey
}

/**
 * Persist an edit buffer: create/update when it has content, delete an emptied existing note.
 * [confirmCleanup] gates a dirty title behind a confirm dialog (used by the editor's explicit "Done"
 * action) vs. silently auto-cleaning (used when an editor is implicitly flushed by switching away —
 * interrupting that with a modal about the note being left behind would be surprising). Returns a
 * [PendingNoteCleanup] iff confirmation is needed (caller must decide what to do); null means the
 * buffer was fully handled (saved, deleted, or was empty).
 */
private fun commitEdit(buf: EditBuffer?, stateManager: NotesStateManager, confirmCleanup: Boolean = false): PendingNoteCleanup? {
    if (buf == null) return null
    val title = buf.title.trim().ifBlank { null }
    val text = buf.text.trim()
    val empty = title == null && text.isEmpty()
    if (empty) {
        if (buf.id != null) stateManager.deleteNoteById(buf.id)
        return null
    }
    val candidate = Note(id = buf.id ?: 0, title = title, text = text, createdAt = System.currentTimeMillis(), categoryId = buf.categoryId)
    if (!confirmCleanup) {
        saveNote(buf.id, NoteSanitizer.sanitize(candidate), stateManager)
        return null
    }
    return when (val decision = NoteSanitizer.decideForSave(candidate, RecordSource.MANUAL_UI)) {
        is SaveDecision.Proceed -> {
            saveNote(buf.id, decision.record, stateManager)
            null
        }
        is SaveDecision.ConfirmCleanup -> PendingNoteCleanup(buf.id, decision.original, decision.dirtyFields)
    }
}

private fun saveNote(id: Long?, note: Note, stateManager: NotesStateManager) {
    if (id == null) stateManager.addNote(note.title, note.text, note.categoryId) else stateManager.updateNoteFields(id, note.title, note.text, note.categoryId)
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

