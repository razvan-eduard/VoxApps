package com.voxapps.calendarapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voxapps.calendar.CalendarView
import com.voxapps.calendarapp.data.CalendarLayer
import com.voxapps.calendarapp.domain.llm.CalendarScanRequestSender
import com.voxapps.calendarapp.state.CalendarStateManager
import com.voxapps.calendarapp.state.CalendarUiState
import com.voxapps.calendarapp.state.CalendarViewMode
import com.voxapps.design.DoubleBackToExitHandler
import com.voxapps.design.rememberRequirementGate
import com.voxapps.ipc.VoxAppsDiscovery
import com.voxapps.ipc.VoxIpc
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/** Pre-expansion window for recurring entries — see [toCalendarItems]'s doc comment. */
private const val WINDOW_PAST_DAYS = 365L
private const val WINDOW_FUTURE_DAYS = 730L
private const val DAY_MILLIS = 24 * 3600_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    state: CalendarUiState.Unlocked,
    language: String,
    stateManager: CalendarStateManager,
    onAddEntry: () -> Unit,
    onEditEntry: (EntryCalendarItem) -> Unit,
    onOpenSettings: () -> Unit
) {
    val languageManager = LocalLanguageManager.current
    val layerById = remember(state.layers) { state.layers.associateBy { it.id } }
    val locale = Locale.forLanguageTag(language)
    var daySummaryFor by remember { mutableStateOf<Long?>(null) }
    var sidebarVisible by remember { mutableStateOf(false) }

    DoubleBackToExitHandler(message = languageManager.getString("press_back_again_to_exit"))

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(languageManager.getString("calendar_title")) },
                navigationIcon = {
                    IconButton(onClick = { sidebarVisible = !sidebarVisible }) {
                        Icon(Icons.Filled.Menu, contentDescription = languageManager.getString("toggle_sidebar"))
                    }
                },
                actions = {
                    val context = LocalContext.current
                    // Scan needs Vision installed to even launch, and Commander installed for the
                    // OCR-cleanup step that runs after — stays visible but dimmed, with an
                    // explanatory toast on tap naming whichever one is missing, rather than silently
                    // failing (or crashing, for the Vision case). Mirrors vox-notes'/vox-expenses'
                    // identical gate.
                    val visionInstalled = remember { VoxAppsDiscovery.isAppInstalled(context, VoxIpc.VISION_PACKAGE) }
                    val commanderInstalled = remember { VoxAppsDiscovery.isCommanderInstalled(context) }
                    val scanGate = rememberRequirementGate(
                        satisfied = visionInstalled && commanderInstalled,
                        requiredMessage = languageManager.getString(
                            if (!visionInstalled) "vision_required_message" else "commander_required_message"
                        )
                    ) { CalendarScanRequestSender.send(context) }
                    Surface(
                        onClick = scanGate.onClick,
                        shape = RoundedCornerShape(percent = 50),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 4.dp)
                            .alpha(scanGate.alpha)
                            .semantics { contentDescription = languageManager.getString("scan_calendar_entry") }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Filled.DocumentScanner,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                languageManager.getString("scan_action"),
                                color = MaterialTheme.colorScheme.onPrimary,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = languageManager.getString("settings"))
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddEntry) {
                Icon(Icons.Filled.Add, contentDescription = languageManager.getString("add_item"))
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
                    stateManager = stateManager
                )
            }
            Box(modifier = Modifier.fillMaxSize()) {
                when (state.viewMode) {
                    CalendarViewMode.YEAR -> YearView(
                        items = items,
                        layers = state.layers,
                        selectedDateMillis = state.selectedDateMillis,
                        locale = locale,
                        onDayClick = { millis ->
                            stateManager.setSelectedDate(millis)
                            stateManager.setViewMode(CalendarViewMode.DAY)
                        }
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
                            itemContent = { item ->
                                EntryRow(
                                    item = item,
                                    layer = layerById[item.entryWithTags.entry.layerId],
                                    onClick = { onEditEntry(item) }
                                )
                            }
                        )
                    }
                    CalendarViewMode.WEEK -> WeekView(
                        items = items,
                        layers = state.layers,
                        selectedDateMillis = state.selectedDateMillis,
                        locale = locale,
                        onItemClick = onEditEntry,
                        onDayHeaderClick = { millis ->
                            stateManager.setSelectedDate(millis)
                            stateManager.setViewMode(CalendarViewMode.DAY)
                        }
                    )
                    CalendarViewMode.DAY -> DayView(
                        items = items,
                        layers = state.layers,
                        selectedDateMillis = state.selectedDateMillis,
                        locale = locale,
                        onItemClick = onEditEntry,
                        onOpenDaySummary = { daySummaryFor = it }
                    )
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
}

@Composable
private fun EntryRow(item: EntryCalendarItem, layer: CalendarLayer?, onClick: () -> Unit) {
    val entry = item.entryWithTags.entry
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(
                        color = layer?.let { Color(it.colorArgb.toInt()) } ?: MaterialTheme.colorScheme.primary,
                        shape = CircleShape
                    )
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = entry.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (!entry.allDay) {
                Text(
                    text = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(item.occurrenceStartMillis)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
