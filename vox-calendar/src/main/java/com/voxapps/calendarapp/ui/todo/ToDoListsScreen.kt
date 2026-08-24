package com.voxapps.calendarapp.ui.todo

import androidx.compose.ui.platform.LocalContext
import com.voxapps.calendarapp.CalendarApplication
import com.voxapps.onboarding.VoxHintKeys
import com.voxapps.onboarding.VoxHintDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxapps.calendarapp.data.CalendarLayer
import com.voxapps.calendarapp.data.ToDoRepository
import com.voxapps.calendarapp.ui.LocalLanguageManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Top-level list of [com.voxapps.calendarapp.data.ToDoList]s — each one rendered inline as a
 * flippable [ToDoListCard] (view/edit faces), mirroring vox-notes' `NotesScreen` shape (one screen,
 * per-item cards, no separate detail screen) rather than a list-then-detail-screen navigation.
 * The FAB opens a brand-new, empty-titled list directly in edit mode — same "opens straight into
 * editing" convention as tapping Notes' FAB.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToDoListsScreen(
    toDoRepository: ToDoRepository,
    defaultLayer: CalendarLayer,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    // Widget deep-links: a list id to open flipped to its edit face, and a "create a new list in
    // edit mode" request — each paired with a trigger counter so repeating the same request fires
    // again (same counter convention as CalendarActivity's editEntryTrigger).
    openListEditId: Long? = null,
    openListEditTrigger: Int = 0,
    quickAddListTrigger: Int = 0,
    // A widget/grid tap on a single node: scroll its list's card into view and open that item's
    // INLINE editor (no modal) — same trigger-counter convention as the list deep-links above.
    openTaskListId: Long? = null,
    openTaskItemId: Long = -1L,
    openTaskTrigger: Int = 0
) {
    val languageManager = LocalLanguageManager.current
    VoxHintDialog(
        store = (LocalContext.current.applicationContext as CalendarApplication).container.hintStore,
        hintKey = VoxHintKeys.TODO_LISTS,
        title = languageManager.getString("hint_todo_lists_title"),
        body = languageManager.getString("hint_todo_lists_body"),
        okLabel = languageManager.getString("hint_ok"),
        dontShowAgainLabel = languageManager.getString("hint_dont_show_again")
    )
    val scope = rememberCoroutineScope()
    val lists by toDoRepository.lists.collectAsStateWithLifecycle(initialValue = emptyList())
    val listState = rememberLazyListState()
    // Single source of truth for "which card (if any) is flipped to its edit face" — hoisted here
    // rather than owned per-card so there's exactly one BackHandler deciding what a system back press
    // does, instead of two independently-registered handlers (this screen's + each card's) whose
    // relative dispatch order isn't guaranteed to close the card before leaving the screen.
    var editingListId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(openListEditTrigger) {
        if (openListEditTrigger > 0 && openListEditId != null) {
            editingListId = openListEditId
            lists.indexOfFirst { it.id == openListEditId }.takeIf { it >= 0 }
                ?.let { listState.animateScrollToItem(it) }
        }
    }
    LaunchedEffect(openTaskTrigger) {
        if (openTaskTrigger > 0 && openTaskListId != null) {
            // The lists Flow may not have emitted yet on a cold open — read the repository directly
            // so the scroll target is the real row set, not the initial empty value.
            val loaded = toDoRepository.lists.first()
            loaded.indexOfFirst { it.id == openTaskListId }.takeIf { it >= 0 }
                ?.let { listState.animateScrollToItem(it) }
        }
    }
    LaunchedEffect(quickAddListTrigger) {
        if (quickAddListTrigger > 0) {
            val id = toDoRepository.createList("", defaultLayer.id)
            editingListId = id
            listState.animateScrollToItem(0)
        }
    }

    // A card flipped to edit anywhere below (or scrolled past) the fold snaps into view — the
    // edit face is about to take most of the screen, so it should start ON the screen.
    LaunchedEffect(editingListId) {
        val id = editingListId ?: return@LaunchedEffect
        lists.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { listState.animateScrollToItem(it) }
    }

    BackHandler {
        if (editingListId != null) editingListId = null else onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                // Tapping the heading itself commits whatever card is open back to view mode —
                // edits persist as they're made, so "save and exit" is exactly closing the flip.
                title = {
                    Text(
                        languageManager.getString("todo_lists_title"),
                        modifier = Modifier.clickable { editingListId = null }
                    )
                },
                // Only shown while a card is flipped to its edit face — closes that card back to view
                // mode. Returning all the way to Calendar is a peer "flip" action (below), not a
                // "back" pop, so there's no navigationIcon for it once no card is being edited.
                navigationIcon = {
                    if (editingListId != null) {
                        IconButton(onClick = { editingListId = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = languageManager.getString("back"))
                        }
                    }
                },
                // Mirrors CalendarScreen's own header: same slot its "open to-do lists" Checklist icon
                // sits in, just pointed the other way — flips back to Calendar via the same 3D-rotation
                // transition instead of a plain screen pop.
                actions = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.CalendarMonth, contentDescription = languageManager.getString("todo_back_to_calendar"))
                    }
                    // The gear never leaves: flipping between Calendar and To-do swaps the OTHER
                    // header action (which grid you jump to), settings stays reachable from both.
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = languageManager.getString("settings"))
                    }
                }
            )
        },
        floatingActionButton = {
            // While a card is flipped to its edit face the FAB shrinks out of the way — the big
            // centered button was overlapping the card being edited; the list also gains bottom
            // padding (below) so the card can always scroll fully clear of it.
            val editingOpen = editingListId != null
            FloatingActionButton(
                modifier = Modifier.size(if (editingOpen) 40.dp else 64.dp),
                onClick = {
                    scope.launch {
                        val id = toDoRepository.createList("", defaultLayer.id)
                        editingListId = id
                        listState.animateScrollToItem(0)
                    }
                }
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = languageManager.getString("todo_add_list"),
                    modifier = Modifier.size(if (editingOpen) 20.dp else 32.dp)
                )
            }
        },
        floatingActionButtonPosition = FabPosition.Center
    ) { padding ->
        if (lists.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(languageManager.getString("todo_lists_empty"))
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
                // Bottom clearance sized past the FAB for EVERY card, both faces — a long card in
                // read mode could scroll under the button just as easily as an edit face.
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 112.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(lists, key = { it.id }) { list ->
                    val items by toDoRepository.itemsForList(list.id).collectAsStateWithLifecycle(initialValue = emptyList())
                    ToDoListCard(
                        list = list,
                        items = items,
                        toDoRepository = toDoRepository,
                        isEditing = list.id == editingListId,
                        onEditingChange = { editing -> editingListId = if (editing) list.id else null },
                        onDeleteList = { scope.launch { toDoRepository.deleteList(list) } },
                        openTaskId = if (list.id == openTaskListId) openTaskItemId else -1L,
                        openTaskTrigger = if (list.id == openTaskListId) openTaskTrigger else 0
                    )
                }
            }
        }
    }
}
