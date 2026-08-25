package com.voxapps.calendarapp.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import com.voxapps.design.selection.VoxSelectionBackHandler
import com.voxapps.design.selection.rememberVoxSelection
import com.voxapps.design.selection.voxSelectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.BurstMode
import androidx.compose.material.icons.filled.CalendarViewDay
import androidx.compose.material.icons.filled.CalendarViewMonth
import androidx.compose.material.icons.filled.CalendarViewWeek
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxapps.attachments.ui.rememberVisionCaptureLauncher
import com.voxapps.calendar.CalendarView
import com.voxapps.calendarapp.CalendarApplication
import com.voxapps.calendarapp.data.CalendarAttachments
import com.voxapps.calendarapp.data.CalendarLayer
import com.voxapps.calendarapp.data.CalendarLayerKind
import com.voxapps.calendarapp.data.preferences.CalendarSettings
import com.voxapps.calendarapp.data.toToDoItem
import com.voxapps.calendarapp.state.CalendarStateManager
import com.voxapps.calendarapp.state.CalendarUiState
import com.voxapps.calendarapp.state.CalendarViewMode
import com.voxapps.calendarapp.ui.todo.TaskChip
import com.voxapps.calendarapp.ui.todo.TimelineNode
import com.voxapps.design.DoubleBackToExitHandler
import com.voxapps.design.SpeedDialAction
import com.voxapps.design.SpeedDialFab
import com.voxapps.design.effects.TodayEffect
import com.voxapps.design.effects.TodayEffectStyle
import com.voxapps.ipc.VoxAppsDiscovery
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxOcrRequest
import com.voxapps.calendarapp.domain.llm.LlmTasks
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import com.voxapps.design.color.VoxColorPalette

/** Pre-expansion window for recurring entries — see [toCalendarItems]'s doc comment. */
private const val WINDOW_PAST_DAYS = 365L
private const val WINDOW_FUTURE_DAYS = 730L
private const val DAY_MILLIS = 24 * 3600_000L

/** Zoom-out cycle for the top-bar mode toggle: Day -> Week -> Month -> Year -> Day. */
private fun nextViewMode(mode: CalendarViewMode): CalendarViewMode = when (mode) {
    CalendarViewMode.DAY -> CalendarViewMode.WEEK
    CalendarViewMode.WEEK -> CalendarViewMode.MONTH
    CalendarViewMode.MONTH -> CalendarViewMode.YEAR
    CalendarViewMode.YEAR -> CalendarViewMode.DAY
}

private fun viewModeIcon(mode: CalendarViewMode): ImageVector = when (mode) {
    CalendarViewMode.DAY -> Icons.Filled.CalendarViewDay
    CalendarViewMode.WEEK -> Icons.Filled.CalendarViewWeek
    CalendarViewMode.MONTH -> Icons.Filled.CalendarViewMonth
    CalendarViewMode.YEAR -> Icons.Filled.DateRange
}

private fun viewModeLabelKey(mode: CalendarViewMode): String = when (mode) {
    CalendarViewMode.DAY -> "view_day"
    CalendarViewMode.WEEK -> "view_week"
    CalendarViewMode.MONTH -> "view_month"
    CalendarViewMode.YEAR -> "view_year"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    state: CalendarUiState.Unlocked,
    settings: CalendarSettings,
    stateManager: CalendarStateManager,
    onAddEntry: () -> Unit,
    onEditEntry: (EntryCalendarItem) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenToDoLists: () -> Unit,
    todayEffect: TodayEffect = TodayEffect.NONE,
    todayEffectStyle: TodayEffectStyle = TodayEffectStyle.RING,
    todayEffectPrimaryColor: Color = Color(0xFFFF6D00),
    todayEffectSecondaryColor: Color? = null,
    todayEffectSpeed: Float = 1f
) {
    val languageManager = LocalLanguageManager.current
    val layerById = remember(state.layers) { state.layers.associateBy { it.id } }
    val locale = Locale.forLanguageTag(settings.language)
    var daySummaryFor by remember { mutableStateOf<Long?>(null) }
    var sidebarVisible by remember { mutableStateOf(false) }

    // --- Day/Week multi-select (see SelectionActionBar) ---
    val selection = rememberVoxSelection<Long>()
    var showBulkDeleteConfirm by remember { mutableStateOf(false) }
    var showMoveExistingPicker by remember { mutableStateOf(false) }
    var showMoveNewForm by remember { mutableStateOf(false) }

    val effectiveOnItemClick: (EntryCalendarItem) -> Unit = { item ->
        selection.tap(item.entryWithTags.entry.id) { onEditEntry(item) }
    }
    // A subscribed calendar's entries are view-only end-to-end — long-pressing one never enters
    // selection mode, since there's nothing a batch delete/move could meaningfully do to it (it would
    // just come back, or get wiped, on the next sync anyway).
    val effectiveOnItemLongClick: (EntryCalendarItem) -> Unit = { item ->
        val entry = item.entryWithTags.entry
        if (layerById[entry.layerId]?.kind != CalendarLayerKind.SUBSCRIBED) selection.start(entry.id)
    }

    // Leaving the app is what back means only when there is nothing smaller to leave first.
    DoubleBackToExitHandler(
        message = languageManager.getString("press_back_again_to_exit"),
        enabled = !selection.active
    )
    VoxSelectionBackHandler(selection)

    val context = LocalContext.current
    val visionInstalled = remember { VoxAppsDiscovery.isAppInstalled(context, VoxIpc.VISION_PACKAGE) }
    val commanderInstalled = remember { VoxAppsDiscovery.isCommanderInstalled(context) }

    val scanSingle = rememberVisionCaptureLauncher(
        baseTask = LlmTasks.CALENDAR_SCAN_CLEANUP, hint = null, produceOCR = true,
        captureMode = VoxOcrRequest.CAPTURE_MODE_SINGLE
    )
    val scanStitch = rememberVisionCaptureLauncher(
        baseTask = LlmTasks.CALENDAR_SCAN_CLEANUP, hint = null, produceOCR = true,
        captureMode = VoxOcrRequest.CAPTURE_MODE_STITCH
    )
    val scanBatch = rememberVisionCaptureLauncher(
        baseTask = LlmTasks.CALENDAR_SCAN_CLEANUP, hint = null, produceOCR = true,
        captureMode = VoxOcrRequest.CAPTURE_MODE_BATCH
    )

    fun gatedScan(action: () -> Unit) {
        if (visionInstalled && commanderInstalled) {
            action()
        } else {
            com.voxapps.design.showRequirementToast(
                context,
                languageManager.getString(if (!visionInstalled) "vision_required_message" else "commander_required_message")
            )
        }
    }

    val scanActions = listOf(
        SpeedDialAction(Icons.Filled.PhotoCamera, languageManager.getString("capture_mode_single")) { gatedScan(scanSingle) },
        SpeedDialAction(Icons.Filled.Layers, languageManager.getString("capture_mode_stitch")) { gatedScan(scanStitch) },
        SpeedDialAction(Icons.Filled.BurstMode, languageManager.getString("capture_mode_batch")) { gatedScan(scanBatch) }
    )

    Scaffold(
        topBar = {
            if (selection.active) {
                SelectionActionBar(
                    selectedCount = selection.size,
                    onClose = { selection.clear() },
                    onDelete = { showBulkDeleteConfirm = true },
                    onMoveExisting = { showMoveExistingPicker = true },
                    onMoveNew = { showMoveNewForm = true }
                )
            } else {
                TopAppBar(
                    title = { Text(languageManager.getString("calendar_title")) },
                    navigationIcon = {
                        IconButton(onClick = { sidebarVisible = !sidebarVisible }) {
                            Icon(Icons.Filled.Menu, contentDescription = languageManager.getString("toggle_sidebar"))
                        }
                    },
                    actions = {
                        // Shows the NEXT mode's icon (a preview of what tapping does), and jumps to
                        // "now" in that granularity — e.g. from Month, it shows Year's icon and
                        // switches straight to the current year rather than wherever Month had scrolled.
                        val nextViewMode = nextViewMode(state.viewMode)
                        IconButton(onClick = {
                            stateManager.setSelectedDate(System.currentTimeMillis())
                            stateManager.setViewMode(nextViewMode)
                        }) {
                            Icon(
                                viewModeIcon(nextViewMode),
                                contentDescription = String.format(
                                    languageManager.getString("switch_to_view_mode_desc"),
                                    languageManager.getString(viewModeLabelKey(nextViewMode))
                                )
                            )
                        }
                        IconButton(onClick = onOpenToDoLists) {
                            Icon(Icons.Filled.Checklist, contentDescription = languageManager.getString("todo_lists_title"))
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = languageManager.getString("settings"))
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                SpeedDialFab(
                    actions = scanActions,
                    mainIcon = Icons.Filled.DocumentScanner,
                    mainContentDescription = languageManager.getString("scan_action"),
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                FloatingActionButton(onClick = onAddEntry) {
                    Icon(Icons.Filled.Add, contentDescription = languageManager.getString("add_item"))
                }
            }
        }
    ) { padding ->
        val now = System.currentTimeMillis()
        val items = remember(state.entries) {
            state.entries.toCalendarItems(
                windowStartMillis = now - WINDOW_PAST_DAYS * DAY_MILLIS,
                windowEndMillis = now + WINDOW_FUTURE_DAYS * DAY_MILLIS
            )
        }
        val dayDots = remember(items, state.layers) {
            val layerById = state.layers.associateBy { it.id }
            items.groupBy {
                com.voxapps.calendar.CalendarDateUtils.millisToLocalDate(it.occurrenceStartMillis)
            }.mapValues { (_, dayItems) ->
                dayItems.mapNotNull { layerById[it.entryWithTags.entry.layerId]?.colorArgb }.distinct()
            }
        }
        Row(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (sidebarVisible) {
                Sidebar(
                    viewMode = state.viewMode,
                    layers = state.layers,
                    availableTags = state.availableTags,
                    selectedTags = state.selectedTags,
                    stateManager = stateManager,
                    settings = settings
                )
            }
            // Book-page-flip between Year/Month/Week/Day, whichever way the mode changed (sidebar
            // tap or the top-bar cycle button) — same rotationY idiom as CalendarRoot's Calendar<->
            // To-do flip, generalized from a fixed 2-state toggle (which can just pin its animated
            // value at 0 or 180 forever) to an arbitrary mode change: every transition here sweeps a
            // fresh 0->180 and then snaps back to 0 the instant it settles (invisible — by then
            // [previousViewMode] already matches [state.viewMode], so which branch renders is moot),
            // rather than accumulating +180 per change, which would need the front/back branch test
            // to somehow know which specific transition is live — not just "how far have we rotated".
            val flipRotationAnim = remember { Animatable(0f) }
            var previousViewMode by remember { mutableStateOf(state.viewMode) }
            LaunchedEffect(state.viewMode) {
                if (state.viewMode != previousViewMode) {
                    flipRotationAnim.animateTo(180f, animationSpec = if (settings.animationsEnabled) tween(450) else tween(0))
                    previousViewMode = state.viewMode
                    flipRotationAnim.snapTo(0f)
                }
            }
            val flipRotation = flipRotationAnim.value
            val flipDensity = LocalDensity.current

            @Composable
            fun ViewModeContent(mode: CalendarViewMode) {
                when (mode) {
                    CalendarViewMode.YEAR -> YearView(
                        items = items,
                        layers = state.layers,
                        selectedDateMillis = state.selectedDateMillis,
                        locale = locale,
                        onDayClick = { millis ->
                            stateManager.setSelectedDate(millis)
                            stateManager.setViewMode(CalendarViewMode.DAY)
                        },
                        onMonthClick = { millis ->
                            stateManager.setSelectedDate(millis)
                            stateManager.setViewMode(CalendarViewMode.MONTH)
                        },
                        todayEffect = todayEffect,
                        todayEffectStyle = todayEffectStyle,
                        todayEffectPrimaryColor = todayEffectPrimaryColor,
                        todayEffectSecondaryColor = todayEffectSecondaryColor,
                        todayEffectSpeed = todayEffectSpeed
                    )
                    CalendarViewMode.MONTH -> {
                        CalendarView(
                            items = items,
                            modifier = Modifier.fillMaxSize(),
                            locale = locale,
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
                            itemContent = { item ->
                                EntryRow(
                                    item = item,
                                    layer = layerById[item.entryWithTags.entry.layerId],
                                    onClick = { effectiveOnItemClick(item) },
                                    isSelected = item.entryWithTags.entry.id in selection,
                                    onLongClick = { effectiveOnItemLongClick(item) }
                                )
                            }
                        )
                    }
                    CalendarViewMode.WEEK -> WeekView(
                        items = items,
                        layers = state.layers,
                        selectedDateMillis = state.selectedDateMillis,
                        locale = locale,
                        onItemClick = effectiveOnItemClick,
                        onDayHeaderClick = { millis ->
                            stateManager.setSelectedDate(millis)
                            stateManager.setViewMode(CalendarViewMode.DAY)
                        },
                        todayEffect = todayEffect,
                        todayEffectStyle = todayEffectStyle,
                        todayEffectPrimaryColor = todayEffectPrimaryColor,
                        todayEffectSecondaryColor = todayEffectSecondaryColor,
                        todayEffectSpeed = todayEffectSpeed,
                        selectedIds = selection.ids,
                        onItemLongClick = effectiveOnItemLongClick,
                        // Selecting the new week's Monday is enough: WeekView derives the week it
                        // shows from whatever date is selected.
                        onWeekChange = { millis -> stateManager.setSelectedDate(millis) },
                        animationsEnabled = settings.animationsEnabled
                    )
                    CalendarViewMode.DAY -> DayView(
                        items = items,
                        layers = state.layers,
                        selectedDateMillis = state.selectedDateMillis,
                        locale = locale,
                        onItemClick = effectiveOnItemClick,
                        onOpenDaySummary = { daySummaryFor = it },
                        todayEffect = todayEffect,
                        todayEffectStyle = todayEffectStyle,
                        todayEffectPrimaryColor = todayEffectPrimaryColor,
                        todayEffectSecondaryColor = todayEffectSecondaryColor,
                        todayEffectSpeed = todayEffectSpeed,
                        selectedIds = selection.ids,
                        onItemLongClick = effectiveOnItemLongClick,
                        onNavigateDay = { delta ->
                            val zoneId = java.time.ZoneId.systemDefault()
                            val newDate = java.time.Instant.ofEpochMilli(state.selectedDateMillis)
                                .atZone(zoneId).toLocalDate().plusDays(delta.toLong())
                            stateManager.setSelectedDate(newDate.atStartOfDay(zoneId).toInstant().toEpochMilli())
                        }
                    )
                }
            }

            Box(
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    rotationY = flipRotation
                    cameraDistance = 12f * flipDensity.density
                }
            ) {
                if (flipRotation <= 90f) {
                    ViewModeContent(previousViewMode)
                } else {
                    Box(Modifier.graphicsLayer { rotationY = 180f }) {
                        ViewModeContent(state.viewMode)
                    }
                }
            }
        }
    }

    daySummaryFor?.let { dayMillis ->
        val zoneId = ZoneId.systemDefault()
        val date = Instant.ofEpochMilli(dayMillis).atZone(zoneId).toLocalDate()
        val itemsForDay = remember(state.entries, dayMillis) {
            state.entries.toCalendarItems(
                windowStartMillis = date.atStartOfDay(zoneId).toInstant().toEpochMilli(),
                windowEndMillis = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli() - 1
            )
        }
        DaySummarySheet(
            dayMillis = dayMillis,
            calendarItemsForDay = itemsForDay,
            layers = state.layers,
            onDismiss = { daySummaryFor = null },
            onEditEntry = {
                daySummaryFor = null
                onEditEntry(it)
            }
        )
    }

    if (showBulkDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showBulkDeleteConfirm = false },
            title = { Text(String.format(languageManager.getString("selection_delete_confirm_title"), selection.size)) },
            text = { Text(languageManager.getString("selection_delete_confirm_desc")) },
            confirmButton = {
                TextButton(onClick = {
                    stateManager.bulkDeleteEntries(selection.ids.toList())
                    showBulkDeleteConfirm = false
                    selection.clear()
                }) { Text(languageManager.getString("selection_delete"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showBulkDeleteConfirm = false }) { Text(languageManager.getString("cancel")) }
            }
        )
    }

    if (showMoveExistingPicker) {
        val localLayers = state.layers.filter { it.kind == CalendarLayerKind.LOCAL }
        AlertDialog(
            onDismissRequest = { showMoveExistingPicker = false },
            title = { Text(languageManager.getString("selection_move_existing")) },
            text = {
                Column {
                    localLayers.forEach { layer ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                stateManager.bulkMoveEntries(selection.ids.toList(), layer.id)
                                showMoveExistingPicker = false
                                selection.clear()
                            }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.size(12.dp)
                                    .background(color = Color(layer.colorArgb.toInt()), shape = CircleShape)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(layer.name)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showMoveExistingPicker = false }) { Text(languageManager.getString("cancel")) }
            }
        )
    }

    if (showMoveNewForm) {
        var newLayerName by remember { mutableStateOf("") }
        var newLayerColor by remember {
            mutableStateOf(VoxColorPalette.unusedOrRandomColor(state.layers.map { it.colorArgb }))
        }
        AlertDialog(
            onDismissRequest = { showMoveNewForm = false },
            title = { Text(languageManager.getString("selection_move_new")) },
            text = {
                Column {
                    androidx.compose.material3.OutlinedTextField(
                        value = newLayerName,
                        onValueChange = { newLayerName = it },
                        label = { Text(languageManager.getString("layer_name")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    com.voxapps.design.color.VoxColorSwatchPicker(
                        selectedColor = newLayerColor,
                        onColorSelected = { newLayerColor = it },
                        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
                        customColorDialogTitle = languageManager.getString("custom_color_title"),
                        customColorUseLabel = languageManager.getString("use_color_button"),
                        customColorCancelLabel = languageManager.getString("cancel"),
                        customColorHueLabel = languageManager.getString("hue_label"),
                        customColorSaturationLabel = languageManager.getString("saturation_label"),
                        customColorBrightnessLabel = languageManager.getString("brightness_label")
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        stateManager.createLayerAndMoveEntries(newLayerName, newLayerColor, selection.ids.toList())
                        showMoveNewForm = false
                        selection.clear()
                    },
                    enabled = newLayerName.isNotBlank()
                ) { Text(languageManager.getString("save")) }
            },
            dismissButton = {
                TextButton(onClick = { showMoveNewForm = false }) { Text(languageManager.getString("cancel")) }
            }
        )
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun EntryRow(
    item: EntryCalendarItem,
    layer: CalendarLayer?,
    onClick: () -> Unit,
    isSelected: Boolean = false,
    onLongClick: () -> Unit = {}
) {
    val entry = item.entryWithTags.entry
    // A to-do-flavored entry (bleeding into the grid via the todoBleedToCalendar setting) reuses the
    // to-do list's own bullet (TimelineNode, incl. the star shape for isImportant) and pill (TaskChip)
    // exactly — same color/size/shape as its own row in the to-do list, so it reads unmistakably as a
    // task here too, not an ordinary calendar entry.
    val isTodoFlavored = entry.listId != null
    val context = LocalContext.current
    val container = remember { (context.applicationContext as CalendarApplication).container }
    val hasAttachments by remember(entry.id) {
        container.attachmentDao.observeFor(CalendarAttachments.RECORD_TYPE, entry.id)
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .voxSelectable(selected = isSelected, onClick = onClick, onLongClick = onLongClick)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isTodoFlavored) {
                TimelineNode(
                    colorArgb = entry.colorArgb ?: 0xFF9E9E9EL,
                    done = entry.completed,
                    onClick = onClick,
                    isImportant = entry.isImportant
                )
                Spacer(Modifier.width(10.dp))
                TaskChip(
                    item = entry.toToDoItem(),
                    clickable = false,
                    onClick = {},
                    modifier = Modifier.weight(1f)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            color = layer?.let { Color(it.colorArgb.toInt()) } ?: MaterialTheme.colorScheme.primary,
                            shape = CircleShape
                        )
                )
                Spacer(Modifier.width(10.dp))
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (hasAttachments.isNotEmpty()) {
                        Icon(
                            Icons.Filled.AttachFile,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp).padding(start = 4.dp)
                        )
                    }
                }
            }
            if (!entry.allDay) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(item.occurrenceStartMillis)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
