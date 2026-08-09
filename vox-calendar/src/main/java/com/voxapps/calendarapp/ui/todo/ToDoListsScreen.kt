package com.voxapps.calendarapp.ui.todo

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
    onBack: () -> Unit
) {
    val languageManager = LocalLanguageManager.current
    val scope = rememberCoroutineScope()
    val lists by toDoRepository.lists.collectAsStateWithLifecycle(initialValue = emptyList())
    val listState = rememberLazyListState()
    // Single source of truth for "which card (if any) is flipped to its edit face" — hoisted here
    // rather than owned per-card so there's exactly one BackHandler deciding what a system back press
    // does, instead of two independently-registered handlers (this screen's + each card's) whose
    // relative dispatch order isn't guaranteed to close the card before leaving the screen.
    var editingListId by remember { mutableStateOf<Long?>(null) }

    BackHandler {
        if (editingListId != null) editingListId = null else onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(languageManager.getString("todo_lists_title")) },
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
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                modifier = Modifier.size(64.dp),
                onClick = {
                    scope.launch {
                        val id = toDoRepository.createList("", defaultLayer.id)
                        editingListId = id
                        listState.animateScrollToItem(0)
                    }
                }
            ) {
                Icon(Icons.Filled.Add, contentDescription = languageManager.getString("todo_add_list"), modifier = Modifier.size(32.dp))
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
                contentPadding = PaddingValues(16.dp),
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
                        onDeleteList = { scope.launch { toDoRepository.deleteList(list) } }
                    )
                }
            }
        }
    }
}
