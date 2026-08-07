package com.voxapps.calendarapp.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxapps.calendarapp.data.CalendarEntryWithTags
import com.voxapps.calendarapp.data.preferences.CalendarSettings
import com.voxapps.calendarapp.data.toToDoItem
import com.voxapps.calendarapp.di.CalendarContainer
import com.voxapps.calendarapp.state.CalendarUiState
import com.voxapps.calendarapp.ui.onboarding.CalendarOnboardingFlow
import com.voxapps.calendarapp.ui.settings.SettingsScreen
import com.voxapps.calendarapp.ui.todo.TaskEditDialog
import com.voxapps.calendarapp.ui.todo.ToDoListsScreen
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import com.voxapps.design.VoxDarkMode
import com.voxapps.design.VoxTheme
import com.voxapps.design.effects.TodayEffect
import com.voxapps.design.effects.TodayEffectStyle
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first

private sealed interface EditTarget {
    data object New : EditTarget
    data class Existing(val entry: CalendarEntryWithTags) : EditTarget
    /** A tapped grid entry that's to-do-flavored ([com.voxapps.calendarapp.data.CalendarEntry.listId]
     *  != null) — opens [TaskEditDialog] instead of [com.voxapps.calendarapp.ui.EntryEditScreen]. */
    data class ExistingTodo(val entry: CalendarEntryWithTags) : EditTarget
}

/**
 * Top-level composable: applies the shared theme, provides [LocalLanguageManager], observes
 * [CalendarUiState], and switches between the lock screen (AuthGate), the calendar, the add/edit
 * screen, and settings (mirrors vox-expenses' ExpensesRoot).
 */
@Composable
fun CalendarRoot(
    container: CalendarContainer,
    onUnlockRequest: () -> Unit,
    // Incremented (not a plain Boolean) by CalendarActivity whenever a new "quick add" launch
    // arrives — from onCreate's initial intent AND from onNewIntent if the activity was already
    // running — so a second widget tap while the app is already open still re-triggers even though
    // a Boolean would look "unchanged". 0 = no pending request (the default, normal launch).
    quickAddTrigger: Int = 0,
    // Same shape as quickAddTrigger, for CalendarWidget's tap-a-record-to-edit rows: editEntryId is
    // the tapped entry's id, editEntryTrigger is the counter that forces re-firing even when the
    // same record is tapped twice in a row (two equal ids wouldn't look like a state change).
    editEntryId: Long = -1L,
    editEntryTrigger: Int = 0
) {
    val settings by container.settingsRepository.settingsFlow.collectAsStateWithLifecycle(
        initialValue = CalendarSettings()
    )
    CompositionLocalProvider(LocalLanguageManager provides container.languageManager) {
        VoxTheme(
            darkMode = when (settings.themeDarkMode) {
                CalendarSettings.THEME_LIGHT -> VoxDarkMode.LIGHT
                CalendarSettings.THEME_DARK -> VoxDarkMode.DARK
                else -> VoxDarkMode.SYSTEM
            },
            colored = settings.themeColored
        ) {
            if (!settings.onboardingCompleted) {
                CalendarOnboardingFlow(
                    languageManager = container.languageManager,
                    stateManager = container.calendarStateManager
                )
            } else {
                val ui by container.calendarStateManager.uiState.collectAsStateWithLifecycle()
                var showSettings by remember { mutableStateOf(false) }
                var showToDoLists by remember { mutableStateOf(false) }
                var editTarget by remember { mutableStateOf<EditTarget?>(null) }

                // Widget "Add" tap — set even while locked; once CalendarUiState transitions to
                // Unlocked the `when` below picks EntryEditScreen up automatically, no extra wiring.
                LaunchedEffect(quickAddTrigger) {
                    if (quickAddTrigger > 0) editTarget = EditTarget.New
                }

                // Widget record-row tap — waits for the first Unlocked state (immediately if already
                // unlocked, or after the user authenticates if not) rather than a one-shot snapshot,
                // so tapping a record while locked still opens it once the user unlocks.
                LaunchedEffect(editEntryTrigger) {
                    if (editEntryTrigger > 0 && editEntryId >= 0) {
                        val unlocked = container.calendarStateManager.uiState
                            .filterIsInstance<CalendarUiState.Unlocked>()
                            .first()
                        unlocked.entries.firstOrNull { it.entry.id == editEntryId }?.let {
                            editTarget = if (it.entry.listId != null) EditTarget.ExistingTodo(it) else EditTarget.Existing(it)
                        }
                    }
                }

                // Owning ToDoList lookup for a to-do-flavored grid tap (see EditTarget.ExistingTodo) —
                // observed here (not fetched suspend-style) since it's already a cheap, already-loaded
                // Flow the to-do lists screen itself observes.
                val toDoLists by container.toDoRepository.lists.collectAsStateWithLifecycle(initialValue = emptyList())
                val todoEditScope = rememberCoroutineScope()

                when (val state = ui) {
                    is CalendarUiState.Loading -> Unit
                    is CalendarUiState.Locked -> AuthGate(onUnlockRequest = onUnlockRequest)
                    is CalendarUiState.Unlocked -> {
                        val target = editTarget
                        val defaultLayer = state.layers.firstOrNull { it.isDefault } ?: state.layers.firstOrNull()
                        when {
                            target is EditTarget.ExistingTodo -> {
                                val list = toDoLists.firstOrNull { it.id == target.entry.entry.listId }
                                if (list != null) {
                                    TaskEditDialog(
                                        item = target.entry.entry.toToDoItem(),
                                        list = list,
                                        toDoRepository = container.toDoRepository,
                                        scope = todoEditScope,
                                        onDismiss = { editTarget = null }
                                    )
                                } else {
                                    // List not loaded yet, or deleted concurrently — nothing sane to
                                    // show without list context, so just close rather than crash.
                                    LaunchedEffect(target) { editTarget = null }
                                }
                            }
                            target != null && defaultLayer != null -> EntryEditScreen(
                                existing = (target as? EditTarget.Existing)?.entry,
                                defaultLayer = defaultLayer,
                                layers = state.layers,
                                stateManager = container.calendarStateManager,
                                availableTags = state.availableTags,
                                onDone = { editTarget = null }
                            )
                            showSettings -> SettingsScreen(
                                stateManager = container.calendarStateManager,
                                settingsRepo = container.settingsRepository,
                                calendarRepository = container.calendarRepository,
                                onBack = { showSettings = false }
                            )
                            else -> {
                                // Same horizontal 3D flip ToDoListCard uses for its own view/edit
                                // faces, applied to the Calendar<->To-do-lists screen switch — gated
                                // behind animationsEnabled (tween(0) snaps instantly when off).
                                val effectiveShowToDo = showToDoLists && defaultLayer != null
                                val rotationTarget = if (effectiveShowToDo) 180f else 0f
                                val rotation by animateFloatAsState(
                                    targetValue = rotationTarget,
                                    animationSpec = if (settings.animationsEnabled) tween(450) else tween(0),
                                    label = "calendarToDoFlip"
                                )
                                val density = LocalDensity.current
                                Box(
                                    modifier = Modifier.fillMaxSize().graphicsLayer {
                                        rotationY = rotation
                                        cameraDistance = 12f * density.density
                                    }
                                ) {
                                    if (rotation <= 90f) {
                                        CalendarScreen(
                                            state = state,
                                            settings = settings,
                                            stateManager = container.calendarStateManager,
                                            onAddEntry = { editTarget = EditTarget.New },
                                            onEditEntry = { item ->
                                                editTarget = if (item.entryWithTags.entry.listId != null) {
                                                    EditTarget.ExistingTodo(item.entryWithTags)
                                                } else {
                                                    EditTarget.Existing(item.entryWithTags)
                                                }
                                            },
                                            onOpenSettings = { showSettings = true },
                                            onOpenToDoLists = { showToDoLists = true },
                                            todayEffect = runCatching { TodayEffect.valueOf(settings.todayEffect) }.getOrDefault(TodayEffect.NONE),
                                            todayEffectStyle = runCatching { TodayEffectStyle.valueOf(settings.todayEffectStyle) }.getOrDefault(TodayEffectStyle.RING),
                                            todayEffectPrimaryColor = Color(settings.todayEffectColor.toInt()),
                                            todayEffectSecondaryColor = settings.todayEffectColor2?.let { Color(it.toInt()) },
                                            todayEffectSpeed = settings.todayEffectSpeed
                                        )
                                    } else if (defaultLayer != null) {
                                        Box(Modifier.graphicsLayer { rotationY = 180f }) {
                                            ToDoListsScreen(
                                                toDoRepository = container.toDoRepository,
                                                defaultLayer = defaultLayer,
                                                onBack = { showToDoLists = false }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
