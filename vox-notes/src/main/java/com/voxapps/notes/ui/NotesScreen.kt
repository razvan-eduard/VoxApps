package com.voxapps.notes.ui

import android.content.Context
import android.widget.Toast
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BurstMode
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PhotoCamera
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
import com.voxapps.attachments.ui.rememberVisionCaptureLauncher
import com.voxapps.calendar.CalendarView
import com.voxapps.datahygiene.DirtyField
import com.voxapps.datahygiene.RecordSource
import com.voxapps.datahygiene.SaveDecision
import com.voxapps.datahygiene.decideForSave
import com.voxapps.design.VoxFullscreenSheet
import com.voxapps.attachments.AttachmentFileStore
import com.voxapps.notes.domain.InlineMedia
import com.voxapps.design.DoubleBackToExitHandler
import com.voxapps.design.SpeedDialAction
import com.voxapps.design.SpeedDialFab
import com.voxapps.design.effects.TodayEffect
import com.voxapps.design.effects.TodayEffectStyle
import com.voxapps.design.rememberRequirementGate
import com.voxapps.notes.data.Note
import com.voxapps.notes.data.NoteSanitizer
import com.voxapps.notes.data.NotesAttachments
import com.voxapps.notes.data.NoteWithCategory
import com.voxapps.notes.data.preferences.NotesSettings
import com.voxapps.ipc.VoxAppsDiscovery
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxOcrRequest
import com.voxapps.notes.domain.llm.LlmTasks
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
    settings: NotesSettings,
    onOpenSettings: () -> Unit,
    // Widget "Add"/tap-a-note-to-edit triggers (see NotesRoot's doc comments) — 0 = no pending
    // request. Counters, not plain values, so a repeat of the same request still re-fires.
    quickAddTrigger: Int = 0,
    editNoteId: Long = -1L,
    editNoteTrigger: Int = 0,
    todayEffect: TodayEffect = TodayEffect.NONE,
    todayEffectStyle: TodayEffectStyle = TodayEffectStyle.RING,
    todayEffectPrimaryColor: Color = Color(0xFFFF6D00),
    todayEffectSecondaryColor: Color? = null,
    todayEffectSpeed: Float = 1f
) {
    val languageManager = LocalLanguageManager.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // The new-note editor is always item 0 (see the LazyColumn below), but the list otherwise stays
    // at whatever scroll position the user was previously at — without this, starting a new note
    // while scrolled down leaves its editor entirely off-screen, expanded but invisible.
    val listState = rememberLazyListState()

    var editing by remember { mutableStateOf<EditBuffer?>(null) }
    var showDateSheet by remember { mutableStateOf(false) }
    var pendingDeleteNote by remember { mutableStateOf<Note?>(null) }
    var pendingNoteCleanup by remember { mutableStateOf<PendingNoteCleanup?>(null) }

    LaunchedEffect(quickAddTrigger) {
        if (quickAddTrigger > 0) {
            commitEdit(editing, stateManager, context)
            editing = EditBuffer(id = null, title = "", text = "", categoryId = state.selectedCategoryId)
            listState.animateScrollToItem(0)
        }
    }

    LaunchedEffect(editNoteTrigger) {
        if (editNoteTrigger > 0 && editNoteId >= 0) {
            state.notes.firstOrNull { it.note.id == editNoteId }?.let { nwc ->
                commitEdit(editing, stateManager, context)
                editing = EditBuffer(nwc.note.id, nwc.note.title.orEmpty(), nwc.note.text, nwc.note.categoryId, textHtml = nwc.note.textHtml)
            }
        }
    }

    // While editing, back closes the inline editor instead of exiting; at rest, back re-arms the
    // standard double-press-to-exit flow. `enabled` keeps the two mutually exclusive regardless of
    // Compose's internal back-callback ordering. This never saves (matches the pre-existing behavior
    // here, unrelated to this change) — a new note's staged-but-unlinked attachment files would
    // otherwise leak, so discard them along with the rest of the draft.
    val dismissEditor: () -> Unit = {
        editing?.let { buf ->
            if (buf.id == null) discardPendingAttachments(buf.pendingAttachments, context)
            val savedHtml = buf.id?.let { id -> state.notes.firstOrNull { it.note.id == id }?.note?.textHtml }
            discardSessionInlineMedia(buf.textHtml, savedHtml, context)
        }
        editing = null
    }
    BackHandler(enabled = editing != null, onBack = dismissEditor)
    DoubleBackToExitHandler(
        message = languageManager.getString("press_back_again_to_exit"),
        enabled = editing == null
    )

    val visionInstalled = remember { VoxAppsDiscovery.isAppInstalled(context, VoxIpc.VISION_PACKAGE) }
    val commanderInstalled = remember { VoxAppsDiscovery.isCommanderInstalled(context) }

    val scanSingle = rememberVisionCaptureLauncher(
        baseTask = LlmTasks.NOTE_SCAN_CLEANUP, hint = null, produceOCR = true,
        captureMode = VoxOcrRequest.CAPTURE_MODE_SINGLE
    )
    val scanStitch = rememberVisionCaptureLauncher(
        baseTask = LlmTasks.NOTE_SCAN_CLEANUP, hint = null, produceOCR = true,
        captureMode = VoxOcrRequest.CAPTURE_MODE_STITCH
    )
    val scanBatch = rememberVisionCaptureLauncher(
        baseTask = LlmTasks.NOTE_SCAN_CLEANUP, hint = null, produceOCR = true,
        captureMode = VoxOcrRequest.CAPTURE_MODE_BATCH
    )

    fun gatedScan(action: () -> Unit) {
        if (visionInstalled && commanderInstalled) {
            action()
        } else {
            Toast.makeText(
                context,
                languageManager.getString(if (!visionInstalled) "vision_required_message" else "commander_required_message"),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    val scanActions = listOf(
        SpeedDialAction(Icons.Filled.PhotoCamera, languageManager.getString("capture_mode_single")) { gatedScan(scanSingle) },
        SpeedDialAction(Icons.Filled.Layers, languageManager.getString("capture_mode_stitch")) { gatedScan(scanStitch) },
        SpeedDialAction(Icons.Filled.BurstMode, languageManager.getString("capture_mode_batch")) { gatedScan(scanBatch) }
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
                    Column(horizontalAlignment = Alignment.End) {
                        SpeedDialFab(
                            actions = scanActions,
                            mainIcon = Icons.Filled.DocumentScanner,
                            mainContentDescription = languageManager.getString("scan_note"),
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        FloatingActionButton(onClick = {
                            commitEdit(editing, stateManager, context)
                            editing = EditBuffer(id = null, title = "", text = "", categoryId = state.selectedCategoryId)
                            scope.launch { listState.animateScrollToItem(0) }
                        }) {
                            Icon(Icons.Filled.Add, contentDescription = languageManager.getString("add_note"))
                        }
                    }
                }
            }
        ) { pad ->
            val dayDots = remember(state.notes) {
                state.notes.groupBy {
                    com.voxapps.calendar.CalendarDateUtils.millisToLocalDate(it.note.createdAt)
                }.mapValues { (_, notes) ->
                    notes.mapNotNull { it.category?.colorArgb }.distinct()
                }
            }
            Column(modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
                FilterChipsRow(state = state, stateManager = stateManager)
                if (settings.calendarViewEnabled) {
                    CalendarView(
                        items = state.notes.map(::NoteCalendarItem),
                        modifier = Modifier.fillMaxSize(),
                        locale = java.util.Locale.forLanguageTag(settings.language),
                        todayContentDescription = languageManager.getString("today"),
                        selectedDateMillis = state.selectedDateMillis,
                        isGridView = state.isGridView,
                        onToggleGridView = { stateManager.setIsGridView(!state.isGridView) },
                        onDateSelected = { stateManager.setSelectedDate(it) },
                        dayDots = dayDots,
                        todayEffect = todayEffect,
                        todayEffectStyle = todayEffectStyle,
                        todayEffectPrimaryColor = todayEffectPrimaryColor,
                        todayEffectSecondaryColor = todayEffectSecondaryColor,
                        todayEffectSpeed = todayEffectSpeed,
                        itemContent = { calItem ->
                            CollapsedNoteCard(
                                item = calItem.nwc,
                                onClick = {
                                    commitEdit(editing, stateManager, context)
                                    editing = EditBuffer(
                                        calItem.nwc.note.id,
                                        calItem.nwc.note.title.orEmpty(),
                                        calItem.nwc.note.text,
                                        calItem.nwc.note.categoryId,
                                        textHtml = calItem.nwc.note.textHtml
                                    )
                                }
                            )
                        }
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // New-note draft editor at the top. `draft` is bound once so the reads below
                        // are statically non-null — the old `editing!!` form re-read the nullable
                        // state on every access, and the callbacks in particular run *after*
                        // composition, so an intervening `editing = null` (BackHandler, onDone) made
                        // them a live NPE. Writes deliberately go through `editing?.` rather than
                        // `draft` so they still act on the current value, just without the bang.
                        val draft = editing
                        if (draft != null && draft.id == null) {
                            item(key = "new-note-editor") {
                                NoteEditorCard(
                                    noteId = draft.id,
                                    stateManager = stateManager,
                                    title = draft.title,
                                    text = draft.text,
                                    textHtml = draft.textHtml,
                                    categoryId = draft.categoryId,
                                    categories = state.categories,
                                    pendingAttachments = draft.pendingAttachments,
                                    onTitleChange = { editing = editing?.copy(title = it) },
                                    onContentChange = { plain, html -> editing = editing?.copy(text = plain, textHtml = html) },
                                    onCategoryChange = { editing = editing?.copy(categoryId = it) },
                                    onAddCategory = { name, color, onResult -> stateManager.addCategory(name, color, onResult) },
                                    onPendingAttachmentsChange = { editing = editing?.copy(pendingAttachments = it) },
                                    onDismiss = dismissEditor,
                                    onDone = {
                                        val cleanup = commitEdit(editing, stateManager, context, confirmCleanup = true)
                                        if (cleanup != null) pendingNoteCleanup = cleanup else editing = null
                                    },
                                    onDelete = {
                                        discardPendingAttachments(editing?.pendingAttachments.orEmpty(), context)
                                        discardSessionInlineMedia(editing?.textHtml, null, context)
                                        editing = null
                                    }
                                )
                            }
                        }
                        items(state.notes, key = { it.note.id }) { nwc ->
                            // Same bind-once-then-null-safe-writes split as the draft editor above.
                            val current = editing
                            if (current != null && current.id == nwc.note.id) {
                                NoteEditorCard(
                                    noteId = current.id,
                                    stateManager = stateManager,
                                    title = current.title,
                                    text = current.text,
                                    textHtml = current.textHtml,
                                    categoryId = current.categoryId,
                                    categories = state.categories,
                                    pendingAttachments = current.pendingAttachments,
                                    onTitleChange = { editing = editing?.copy(title = it) },
                                    onContentChange = { plain, html -> editing = editing?.copy(text = plain, textHtml = html) },
                                    onCategoryChange = { editing = editing?.copy(categoryId = it) },
                                    onAddCategory = { name, color, onResult -> stateManager.addCategory(name, color, onResult) },
                                    onPendingAttachmentsChange = { editing = editing?.copy(pendingAttachments = it) },
                                    onDismiss = dismissEditor,
                                    onDone = {
                                        val cleanup = commitEdit(editing, stateManager, context, confirmCleanup = true)
                                        if (cleanup != null) pendingNoteCleanup = cleanup else editing = null
                                    },
                                    onDelete = { pendingDeleteNote = nwc.note }
                                )
                            } else {
                                CollapsedNoteCard(
                                    item = nwc,
                                    onClick = {
                                        commitEdit(editing, stateManager, context)
                                        editing = EditBuffer(nwc.note.id, nwc.note.title.orEmpty(), nwc.note.text, nwc.note.categoryId, textHtml = nwc.note.textHtml)
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
    if (settings.calendarViewEnabled && editing != null) {
        val current = editing!!
        VoxFullscreenSheet(
            onDismiss = {
                if (current.id == null) discardPendingAttachments(current.pendingAttachments, context)
                val savedHtml = current.id?.let { id -> state.notes.firstOrNull { it.note.id == id }?.note?.textHtml }
                discardSessionInlineMedia(current.textHtml, savedHtml, context)
                editing = null
            }
        ) {
            NoteEditorCard(
                noteId = current.id,
                stateManager = stateManager,
                title = current.title,
                text = current.text,
                textHtml = current.textHtml,
                categoryId = current.categoryId,
                categories = state.categories,
                pendingAttachments = current.pendingAttachments,
                onTitleChange = { editing = current.copy(title = it) },
                onContentChange = { plain, html -> editing = current.copy(text = plain, textHtml = html) },
                onCategoryChange = { editing = current.copy(categoryId = it) },
                onAddCategory = { name, color, onResult -> stateManager.addCategory(name, color, onResult) },
                onPendingAttachmentsChange = { editing = current.copy(pendingAttachments = it) },
                onDismiss = dismissEditor,
                onDone = {
                    val cleanup = commitEdit(editing, stateManager, context, confirmCleanup = true)
                    if (cleanup != null) pendingNoteCleanup = cleanup else editing = null
                },
                onDelete = {
                    if (current.id == null) {
                        discardPendingAttachments(current.pendingAttachments, context)
                        discardSessionInlineMedia(current.textHtml, null, context)
                        editing = null
                    } else {
                        pendingDeleteNote = state.notes.firstOrNull { it.note.id == current.id }?.note
                    }
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
                        saveNote(pending.id, NoteSanitizer.sanitize(pending.note), pending.pendingAttachments, stateManager, context)
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

/** Local edit buffer for the inline note editor. [id] == null means a new (unsaved) note.
 *  [pendingAttachments] are files already staged on disk (via [com.voxapps.attachments.AttachmentFileStore])
 *  for a not-yet-saved note — always empty once [id] is non-null, since an existing note's attachments
 *  are read/written straight to the database instead. See [saveNote] and the discard sites below for
 *  where these get linked to the real note id (on save) or deleted (on discard). */
private data class EditBuffer(
    val id: Long?,
    val title: String,
    val text: String,
    val categoryId: Long?,
    val pendingAttachments: List<String> = emptyList(),
    /** The styled twin of [text], kept in step by the editor — see [Note.textHtml]. */
    val textHtml: String? = null
)

/** A dirty save the user needs to accept auto-clean or cancel to fix manually. */
private data class PendingNoteCleanup(
    val id: Long?,
    val note: Note,
    val dirtyFields: List<DirtyField>,
    val pendingAttachments: List<String> = emptyList()
)

/** Deletes every staged-but-unlinked attachment file for a note draft that's being discarded without
 *  ever being saved — the counterpart to [saveNote]'s linking of the same list on a successful save. */
private fun discardPendingAttachments(fileNames: List<String>, context: Context) {
    fileNames.forEach { fileName -> AttachmentFileStore.delete(context, NotesAttachments.DIR, fileName) }
}

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
private fun commitEdit(
    buf: EditBuffer?,
    stateManager: NotesStateManager,
    context: Context,
    confirmCleanup: Boolean = false
): PendingNoteCleanup? {
    if (buf == null) return null
    val title = buf.title.trim().ifBlank { null }
    val text = buf.text.trim()
    val empty = title == null && text.isEmpty()
    if (empty) {
        if (buf.id != null) {
            stateManager.deleteNoteById(buf.id)
        } else {
            // A blank draft with a photo attached still staged real files on disk (title/text being
            // empty doesn't mean nothing was added) — nothing will ever link them, so delete them now.
            discardPendingAttachments(buf.pendingAttachments, context)
            discardSessionInlineMedia(buf.textHtml, null, context)
        }
        return null
    }
    val candidate = Note(
        id = buf.id ?: 0,
        title = title,
        text = text,
        createdAt = System.currentTimeMillis(),
        categoryId = buf.categoryId,
        textHtml = buf.textHtml
    )
    if (!confirmCleanup) {
        saveNote(buf.id, NoteSanitizer.sanitize(candidate), buf.pendingAttachments, stateManager, context)
        return null
    }
    return when (val decision = NoteSanitizer.decideForSave(candidate, RecordSource.MANUAL_UI)) {
        is SaveDecision.Proceed -> {
            saveNote(buf.id, decision.record, buf.pendingAttachments, stateManager, context)
            null
        }
        is SaveDecision.ConfirmCleanup -> PendingNoteCleanup(buf.id, decision.original, decision.dirtyFields, buf.pendingAttachments)
    }
}

/** Saves [note], then links any [pendingAttachments] (already staged on disk, see [EditBuffer]) to
 *  the real note id — for an update this is always an empty list (existing notes' attachments are
 *  already DB-linked directly, never staged locally), so the link step is a no-op there. */
private fun saveNote(id: Long?, note: Note, pendingAttachments: List<String>, stateManager: NotesStateManager, context: Context) {
    if (id == null) {
        stateManager.addNote(note.title, note.text, note.categoryId, textHtml = note.textHtml) { newId ->
            pendingAttachments.forEach { fileName -> stateManager.addManualAttachment(newId, fileName) }
            stateManager.syncInlineAttachments(newId, note.textHtml, context)
        }
    } else {
        stateManager.updateNoteFields(id, note.title, note.text, note.categoryId, textHtml = note.textHtml)
        stateManager.syncInlineAttachments(id, note.textHtml, context)
    }
}

/** Deletes the inline-media files this editing session staged but whose note never saved them —
 *  everything the buffer's markers reference beyond what the note's last saved HTML does. */
private fun discardSessionInlineMedia(draftHtml: String?, savedHtml: String?, context: Context) {
    val draft = InlineMedia.mediaRefs(draftHtml).map { it.fileName }.toSet()
    val saved = InlineMedia.mediaRefs(savedHtml).map { it.fileName }.toSet()
    (draft - saved).forEach { AttachmentFileStore.delete(context, NotesAttachments.DIR, it) }
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
