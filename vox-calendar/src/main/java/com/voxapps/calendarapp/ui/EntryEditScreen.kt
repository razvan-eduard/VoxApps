package com.voxapps.calendarapp.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BurstMode
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxapps.attachments.AttachmentFileStore
import com.voxapps.attachments.ui.AttachmentUiItem
import com.voxapps.attachments.ui.AttachmentsSection
import com.voxapps.attachments.ui.GroupDeleteConfig
import com.voxapps.attachments.ui.rememberCameraCaptureLauncher
import com.voxapps.attachments.ui.rememberVisionCaptureLauncher
import com.voxapps.design.PaperTapField
import com.voxapps.design.openLocationInMaps
import com.voxapps.location.EphemeralLocationStore
import com.voxapps.location.VoxLocationResolver
import com.voxapps.location.ui.VoxLocationPickerField
import com.voxapps.design.SpeedDialAction
import com.voxapps.ipc.VoxOcrRequest
import com.voxapps.design.picklist.Picklist
import com.voxapps.calendarapp.CalendarApplication
import com.voxapps.calendarapp.domain.llm.LlmTasks
import com.voxapps.calendarapp.data.CalendarAttachments
import com.voxapps.calendarapp.data.CalendarEntry
import com.voxapps.calendarapp.data.CalendarEntrySanitizer
import com.voxapps.calendarapp.data.CalendarEntryType
import com.voxapps.calendarapp.data.CalendarEntryWithTags
import com.voxapps.calendarapp.data.CalendarLayer
import com.voxapps.calendarapp.data.CalendarLayerKind
import com.voxapps.calendarapp.data.RecurrenceFrequency
import com.voxapps.calendarapp.data.WeekdayMask
import com.voxapps.calendarapp.data.ReminderOffsetsCodec
import com.voxapps.calendarapp.domain.localization.LanguageManager
import com.voxapps.calendarapp.domain.reminders.ReminderScheduler
import com.voxapps.calendarapp.state.CalendarStateManager
import com.voxapps.datahygiene.DirtyField
import com.voxapps.datahygiene.RecordSource
import com.voxapps.datahygiene.SaveDecision
import com.voxapps.datahygiene.decideForSave
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Date
import java.util.UUID
import com.voxapps.design.VoxSemanticColors

private val OffenseRed = Color(0xFFD32F2F)

/** Colors for the tappable "important" toggle star — same bright-red-on/darker-red-off convention as
 *  the to-do item's own toggle in `ToDoListCard.kt`'s `TaskEditDialog`, since [CalendarEntry
 *  .isImportant] is now a general field, not to-do-exclusive. */
private val IMPORTANT_TOGGLE_ON_COLOR = VoxSemanticColors.importantToggleOn
private val IMPORTANT_TOGGLE_OFF_COLOR = VoxSemanticColors.importantToggleOff

/** Same fixed green as `ToDoNodeTimeline`'s/`ToDoListCard`'s `DONE_CHECK_COLOR` — [completed] is the
 *  exact same underlying field as a to-do item's `done` now (unification — see [CalendarEntry]'s doc
 *  comment), so its toggle here matches that one's icon/color/row style exactly instead of the old
 *  plain Material Checkbox. */
private val DONE_CHECK_COLOR = VoxSemanticColors.done

private data class PendingCleanup(val entry: CalendarEntry, val tags: List<String>, val dirtyFields: List<DirtyField>)

/** Deletes every staged-but-unlinked attachment file for a new entry that's discarded (screen closed
 *  without a title, so nothing was ever saved) — the counterpart to linking the same list once a real
 *  entry id exists. */
private fun discardPendingAttachments(fileNames: List<String>, context: Context) {
    fileNames.forEach { fileName -> AttachmentFileStore.delete(context, CalendarAttachments.DIR, fileName) }
}

private fun entryFieldLabel(languageManager: LanguageManager, fieldKey: String): String = when (fieldKey) {
    "title" -> languageManager.getString("entry_title")
    "description" -> languageManager.getString("entry_description")
    "location" -> languageManager.getString("entry_location")
    else -> fieldKey
}

/**
 * Add/edit screen for a single Event or Task. [title] is the only mandatory field. Follows the same
 * "paper form" visual language as vox-expenses' `ExpenseEditScreen` — underline-only inline-editable
 * text grouped into soft-bordered, shadowed [SectionCard]s — rather than boxed OutlinedTextFields.
 *
 * Phase 1: every entry is assigned [defaultLayer] with no picker UI yet — the layer picker + full
 * layers sidebar lands in Phase 2.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryEditScreen(
    existing: CalendarEntryWithTags?,
    defaultLayer: CalendarLayer,
    layers: List<CalendarLayer>,
    stateManager: CalendarStateManager,
    availableTags: List<String>,
    onDone: () -> Unit
) {
    val languageManager = LocalLanguageManager.current
    val zoneId = ZoneId.systemDefault()
    val context = LocalContext.current

    // The entry's own calendar — starts out matching the existing entry (or defaultLayer for a
    // brand-new one), but tracks selectedLayerId live so switching the picker below immediately
    // reflects any calendar-level reminder override for the newly-chosen calendar too.
    var selectedLayerId by remember { mutableStateOf(existing?.entry?.layerId ?: defaultLayer.id) }
    val currentLayer = remember(selectedLayerId, layers) {
        layers.firstOrNull { it.id == selectedLayerId } ?: defaultLayer
    }
    val calendarReminderOffsets = remember(currentLayer) {
        ReminderOffsetsCodec.decode(currentLayer.reminderOffsetsMinutes)
    }
    val remindersOverriddenByCalendar = calendarReminderOffsets.isNotEmpty()
    // A subscribed calendar's entries are view-only end-to-end (see the plan's confirmed decision) —
    // your own edits would just be overwritten by the next sync anyway. Reminders are the one
    // exception (gated separately above), since a reminder is device-local, not calendar content.
    val isReadOnly = existing != null && currentLayer.kind == CalendarLayerKind.SUBSCRIBED
    val selectableLayers = remember(layers) { layers.filter { it.kind == CalendarLayerKind.LOCAL } }

    var type by remember { mutableStateOf(existing?.entry?.type ?: CalendarEntryType.EVENT) }
    var title by remember { mutableStateOf(existing?.entry?.title ?: "") }
    var description by remember { mutableStateOf(existing?.entry?.description ?: "") }
    var location by remember { mutableStateOf(existing?.entry?.location ?: "") }
    
    // Non-nullable in this screen's own local state: CalendarEntry.startMillis is nullable only to
    // represent a dateless to-do checklist item, and this screen is never reached for those (see
    // CalendarRoot's EditTarget.ExistingTodo routing) — an existing plain Event/Task always has one,
    // so the fallback below is purely defensive, never expected to actually trigger.
    val initialStartMillis = remember {
        val now = java.time.ZonedDateTime.now(zoneId)
        val nextHour = now.truncatedTo(java.time.temporal.ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli()
        existing?.entry?.startMillis ?: nextHour
    }
    var startMillis by remember { mutableStateOf(initialStartMillis) }
    var endMillis by remember { mutableStateOf(existing?.entry?.endMillis) }
    var allDay by remember { mutableStateOf(existing?.entry?.allDay ?: false) }
    var completed by remember { mutableStateOf(existing?.entry?.completed ?: false) }
    var important by remember { mutableStateOf(existing?.entry?.isImportant ?: false) }
    var recurrence by remember { mutableStateOf(existing?.entry?.recurrenceFrequency ?: RecurrenceFrequency.NONE) }
    var recurrenceInterval by remember { mutableStateOf(existing?.entry?.recurrenceInterval ?: 1) }
    var recurrenceUntilMillis by remember { mutableStateOf(existing?.entry?.recurrenceUntilMillis) }
    var recurrenceDaysMask by remember { mutableStateOf(existing?.entry?.recurrenceDaysMask ?: 0) }
    val tags = remember { mutableStateListOf<String>().apply { addAll(existing?.tagNames ?: emptyList()) } }
    var tagInput by remember { mutableStateOf("") }
    var tagMenuExpanded by remember { mutableStateOf(false) }
    val reminderOffsets = remember { mutableStateListOf<Int>() }
    var canScheduleExactAlarms by remember { mutableStateOf(ReminderScheduler.canScheduleExactAlarms(context)) }

    var showStartDatePicker by remember { mutableStateOf(false) }
    var showStartTimePicker by remember { mutableStateOf(false) }
    var isStartTimeSetManually by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }
    var showEndTimePicker by remember { mutableStateOf(false) }
    var isEndTimeSetManually by remember { mutableStateOf(false) }
    var showUntilDatePicker by remember { mutableStateOf(false) }
    
    val isTimeRangeInvalid = remember(type, startMillis, endMillis) {
        isTimeRangeInvalid(type, startMillis, endMillis)
    }
    
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var pendingCleanup by remember { mutableStateOf<PendingCleanup?>(null) }
    // Photos staged (via AttachmentFileStore, no id needed for that) while composing a brand-new
    // entry that doesn't have a real id yet — linked to the real entry id once saveEntry actually
    // creates the row, or deleted if the screen closes without ever saving. Always empty when
    // editing an existing entry (attachments then read/write straight to the database via
    // EntryAttachmentsSection instead).
    var pendingAttachments by remember { mutableStateOf<List<String>>(emptyList()) }

    // Reads the entry's own INDIVIDUAL preference, not whatever's currently scheduled — the two only
    // differ while a calendar-level override is active (see ReminderOffsetsCodec/CalendarLayer's doc
    // comment), in which case the picker below is disabled and pre-checked to the calendar's own
    // offsets instead, so reading the individual value here is still correct either way.
    LaunchedEffect(existing?.entry?.id) {
        val offsets = ReminderOffsetsCodec.decode(existing?.entry?.individualReminderOffsetsMinutes)
        reminderOffsets.addAll(offsets)
    }

    // Granting the exact-alarm permission happens in system Settings, outside this screen — recheck
    // on every resume so the inline notice/button disappears the moment the user grants it, without
    // needing to leave and re-enter this screen.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canScheduleExactAlarms = ReminderScheduler.canScheduleExactAlarms(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun saveEntry(entry: CalendarEntry, entryTags: List<String>) {
        val effectiveReminderOffsets = reminderOffsets.toList()
        if (existing != null) {
            stateManager.recordFieldCorrections(existing.entry, entry)
            stateManager.updateEntry(entry, entryTags, effectiveReminderOffsets)
        } else {
            stateManager.addEntry(
                type = entry.type,
                title = entry.title,
                description = entry.description,
                location = entry.location,
                // Non-null: this screen only ever builds an entry with a real date (see
                // initialStartMillis' doc comment) — the entity field is nullable only for to-do items.
                startMillis = entry.startMillis!!,
                endMillis = entry.endMillis,
                allDay = entry.allDay,
                completed = entry.completed,
                isImportant = entry.isImportant,
                recurrenceFrequency = entry.recurrenceFrequency,
                recurrenceInterval = entry.recurrenceInterval,
                recurrenceUntilMillis = entry.recurrenceUntilMillis,
                recurrenceDaysMask = entry.recurrenceDaysMask,
                layerId = entry.layerId,
                tags = entryTags,
                reminderOffsetsMinutes = effectiveReminderOffsets,
                onResult = { newId ->
                    pendingAttachments.forEach { fileName -> stateManager.addManualAttachment(newId, fileName) }
                    pendingAttachments = emptyList()
                }
            )
        }
    }

    // Shared by the checkmark button, the back arrow, and the system back gesture/button — this
    // screen has no separate "discard changes" path, so leaving it any way always tries to save
    // first. A blank title (the one mandatory field) has nothing meaningful to save, so that case
    // just closes without writing anything, matching the old Save button's `enabled = title.isNotBlank()`
    // guard instead of silently creating an empty-titled entry.
    fun attemptSaveAndClose() {
        // A subscribed calendar's entry is view-only end-to-end — nothing typed/toggled here (other
        // than the reminders section, which saves through its own separate path) is ever persisted.
        if (isReadOnly) {
            onDone()
            return
        }
        if (title.isBlank() || isTimeRangeInvalid) {
            // Nothing will ever link these now — a no-op when editing an existing entry, since
            // pendingAttachments is only ever populated for a new, not-yet-saved one.
            discardPendingAttachments(pendingAttachments, context)
            if (title.isBlank()) onDone() // Close if title is blank, but don't close if only time is invalid
            return
        }
        val effectiveEnd = if (type == CalendarEntryType.EVENT) endMillis else null
        val base = existing?.entry ?: CalendarEntry(
            uid = java.util.UUID.randomUUID().toString(),
            type = type,
            title = title,
            startMillis = startMillis,
            layerId = defaultLayer.id,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        val candidate = base.copy(
            type = type,
            title = title.trim(),
            description = description,
            location = location,
            startMillis = startMillis,
            endMillis = effectiveEnd,
            allDay = allDay,
            completed = completed,
            isImportant = important,
            recurrenceFrequency = recurrence,
            recurrenceInterval = recurrenceInterval,
            recurrenceUntilMillis = recurrenceUntilMillis,
            recurrenceDaysMask = if (recurrence == RecurrenceFrequency.WEEKLY) recurrenceDaysMask else 0,
            layerId = selectedLayerId
        )
        when (val decision = CalendarEntrySanitizer.decideForSave(candidate, RecordSource.MANUAL_UI)) {
            is SaveDecision.Proceed -> {
                saveEntry(decision.record, tags.toList())
                onDone()
            }
            is SaveDecision.ConfirmCleanup -> {
                pendingCleanup = PendingCleanup(decision.original, tags.toList(), decision.dirtyFields)
            }
        }
    }

    BackHandler { attemptSaveAndClose() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Box {
                        if (title.isEmpty()) {
                            // Title is the only mandatory field (see attemptSaveAndClose's blank-title
                            // guard below) — the trailing asterisk is the only cue for that, since this
                            // placeholder doubles as the field's label (no separate label row above it).
                            Row {
                                Text(
                                    languageManager.getString("entry_title"),
                                    style = MaterialTheme.typography.titleLarge,
                                    color = LocalContentColor.current.copy(alpha = 0.5f)
                                )
                                Text(
                                    " *",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        BasicTextField(
                            value = title,
                            onValueChange = { title = it },
                            readOnly = isReadOnly,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.titleLarge.copy(color = LocalContentColor.current),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = ::attemptSaveAndClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = languageManager.getString("back"))
                    }
                },
                actions = {
                    if (!isReadOnly) {
                        if (existing != null) {
                            IconButton(onClick = { showDeleteConfirm = true }) {
                                Icon(Icons.Filled.Delete, contentDescription = languageManager.getString("delete"))
                            }
                        }
                        IconButton(onClick = ::attemptSaveAndClose, enabled = title.isNotBlank() && !isTimeRangeInvalid) {
                            Icon(Icons.Filled.Check, contentDescription = languageManager.getString("save"))
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (isReadOnly) {
                item {
                    Text(
                        languageManager.getString("entry_subscribed_read_only_note"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        enabled = !isReadOnly,
                        selected = type == CalendarEntryType.EVENT,
                        onClick = { type = CalendarEntryType.EVENT },
                        label = { Text(languageManager.getString("entry_type_event")) }
                    )
                    FilterChip(
                        enabled = !isReadOnly,
                        selected = type == CalendarEntryType.TASK,
                        onClick = { type = CalendarEntryType.TASK },
                        label = { Text(languageManager.getString("entry_type_task")) }
                    )
                }
            }

            if (existing?.entry?.id != null) {
                // A subscribed entry is view-only end-to-end — attachments write directly to the DB
                // as soon as you add/remove one (unlike every other field here, which only takes
                // effect through the now-hidden Save button), so this section is skipped entirely
                // rather than merely visually disabled.
                if (!isReadOnly) {
                    item {
                        EntryAttachmentsSection(existing.entry.id, stateManager)
                    }
                }
            } else {
                item {
                    PendingEntryAttachmentsSection(
                        pendingAttachments = pendingAttachments,
                        onChange = { pendingAttachments = it }
                    )
                }
            }

            item {
                SectionCard {
                    SectionTitle(languageManager.getString("entry_details"))
                    PaperField(
                        label = languageManager.getString("entry_description"),
                        value = description,
                        onValueChange = { description = it },
                        singleLine = false,
                        minLines = 2,
                        enabled = !isReadOnly
                    )
                    // A filled location is a LINK first: tapping it opens the saved text as a place
                    // search in whichever maps/nav app the user picks from the chooser; the pencil
                    // switches back to text entry. Blank (or mid-edit) it's the plain field.
                    var locationEditing by remember { mutableStateOf(false) }
                    if (location.isNotBlank() && !locationEditing) {
                        PaperTapField(
                            label = languageManager.getString("entry_location"),
                            value = location,
                            onClick = { openLocationInMaps(context, location) },
                            trailingIcon = {
                                if (!isReadOnly) {
                                    IconButton(onClick = { locationEditing = true }, modifier = Modifier.size(24.dp)) {
                                        Icon(
                                            Icons.Filled.Edit,
                                            contentDescription = languageManager.getString("entry_location"),
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        )
                    } else {
                        // Search-first entry (OpenStreetMap place search + GPS lock) — the GPS
                        // lambda resolves a FRESH fix through a store that remembers nothing:
                        // calendar keeps no location cache by design, unlike expenses/commander.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            VoxLocationPickerField(
                                value = location,
                                onValueChange = { if (!isReadOnly) location = it },
                                label = languageManager.getString("entry_location"),
                                gpsLock = if (isReadOnly) null else {
                                    {
                                        VoxLocationResolver.create(
                                            context, EphemeralLocationStore(), needsReverseGeocode = true
                                        ).resolveLocation()?.displayName
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                            if (locationEditing) {
                                IconButton(onClick = { locationEditing = false }, modifier = Modifier.size(24.dp)) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = languageManager.getString("apply"),
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                    if (isReadOnly) {
                        Text(
                            text = "${languageManager.getString("entry_layer")}: ${currentLayer.name}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Picklist(
                            items = selectableLayers,
                            selected = currentLayer,
                            itemLabel = { it.name },
                            onSelect = { selectedLayerId = it.id },
                            anchor = { value, onClick ->
                                PaperTapField(
                                    label = languageManager.getString("entry_layer"),
                                    value = value,
                                    onClick = onClick,
                                    trailingIcon = {
                                        Icon(Icons.Filled.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    }
                                )
                            }
                        )
                    }
                }
            }

            item {
                SectionCard {
                    SectionTitle(languageManager.getString(if (type == CalendarEntryType.EVENT) "entry_when" else "entry_due"))

                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                        PaperTapField(
                            label = languageManager.getString(if (type == CalendarEntryType.EVENT) "entry_start_date" else "entry_due_date"),
                            value = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(startMillis)),
                            onClick = { showStartDatePicker = true },
                            enabled = !isReadOnly,
                            modifier = Modifier.weight(1f)
                        )
                        if (!allDay) {
                            PaperTapField(
                                label = languageManager.getString("entry_start_time"),
                                value = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(startMillis)),
                                onClick = { showStartTimePicker = true },
                                enabled = !isReadOnly,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    if (type == CalendarEntryType.EVENT) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = allDay, enabled = !isReadOnly, onCheckedChange = { checked ->
                                allDay = checked
                                if (checked) {
                                    startMillis = startOfDay(startMillis, zoneId)
                                    endMillis = endMillis?.let { startOfDay(it, zoneId) }
                                }
                            })
                            Text(languageManager.getString("entry_all_day"))
                        }

                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                PaperTapField(
                                    label = languageManager.getString("entry_end_date"),
                                    value = endMillis?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) }
                                        ?: languageManager.getString("none"),
                                    onClick = { showEndDatePicker = true },
                                    enabled = !isReadOnly,
                                    modifier = Modifier.weight(1f)
                                )
                                if (!allDay) {
                                    PaperTapField(
                                        label = languageManager.getString("entry_end_time"),
                                        value = endMillis?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) }
                                            ?: languageManager.getString("none"),
                                        onClick = { if (endMillis == null) endMillis = startMillis; showEndTimePicker = true },
                                        enabled = !isReadOnly,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }

                            if (endMillis != null && !isReadOnly) {
                                IconButton(
                                    onClick = { endMillis = null },
                                    modifier = Modifier.padding(start = 8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Delete,
                                        contentDescription = languageManager.getString("clear"),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }

                        val currentEndMillis = endMillis
                        if (currentEndMillis != null && isTimeRangeInvalid(type, startMillis, currentEndMillis)) {
                            Text(
                                text = languageManager.getString("entry_time_error"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable(enabled = !isReadOnly) { completed = !completed }
                        ) {
                            Icon(
                                if (completed) Icons.Filled.CheckCircle else Icons.Filled.CheckCircleOutline,
                                contentDescription = null,
                                tint = DONE_CHECK_COLOR
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(languageManager.getString("entry_completed"))
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Icon(
                            if (important) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = null,
                            tint = if (important) IMPORTANT_TOGGLE_ON_COLOR else IMPORTANT_TOGGLE_OFF_COLOR,
                            modifier = Modifier.clickable(enabled = !isReadOnly) { important = !important }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(languageManager.getString("todo_important_event"), modifier = Modifier.weight(1f))
                    }

                    Picklist(
                        items = RecurrenceFrequency.entries,
                        selected = recurrence,
                        itemLabel = { languageManager.getString(recurrenceLabelKey(it)) },
                        onSelect = { freq ->
                            recurrence = freq
                            if (freq == RecurrenceFrequency.NONE) recurrenceUntilMillis = null
                        },
                        anchor = { value, onClick ->
                            PaperTapField(
                                label = languageManager.getString("entry_recurrence"),
                                value = value,
                                onClick = onClick,
                                enabled = !isReadOnly,
                                trailingIcon = {
                                    Icon(Icons.Filled.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                }
                            )
                        }
                    )
                    if (recurrence != RecurrenceFrequency.NONE) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                languageManager.getString("entry_recurrence_every"),
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { recurrenceInterval = (recurrenceInterval - 1).coerceAtLeast(1) },
                                enabled = !isReadOnly && recurrenceInterval > 1
                            ) { Icon(Icons.Filled.Remove, contentDescription = null) }
                            Text(
                                recurrenceInterval.toString(),
                                modifier = Modifier.widthIn(min = 24.dp),
                                textAlign = TextAlign.Center
                            )
                            IconButton(
                                onClick = { recurrenceInterval = (recurrenceInterval + 1).coerceAtMost(365) },
                                enabled = !isReadOnly
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = null)
                            }
                            Text(languageManager.getString(recurrenceIntervalUnitKey(recurrence)))
                        }
                    }
                    if (recurrence == RecurrenceFrequency.WEEKLY) {
                        Text(
                            languageManager.getString("entry_recurrence_days"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        WeekdayPickerRow(
                            mask = recurrenceDaysMask,
                            onToggle = { day -> recurrenceDaysMask = WeekdayMask.toggled(recurrenceDaysMask, day) },
                            labels = { day -> languageManager.getString(weekdayLabelKey(day)) },
                            enabled = !isReadOnly
                        )
                    }
                    if (recurrence != RecurrenceFrequency.NONE) {
                        PaperTapField(
                            label = languageManager.getString("entry_recurrence_until"),
                            value = recurrenceUntilMillis?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) }
                                ?: languageManager.getString("entry_recurrence_forever"),
                            onClick = { showUntilDatePicker = true },
                            enabled = !isReadOnly
                        )
                    }
                }
            }

            item {
                SectionCard {
                    SectionTitle(languageManager.getString("entry_reminders"))
                    if (recurrence != RecurrenceFrequency.NONE) {
                        Text(
                            languageManager.getString("reminder_recurring_next_occurrence_note"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (remindersOverriddenByCalendar) {
                        Text(
                            languageManager.getString("reminder_set_by_calendar_note"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        ReminderOffsetsPicker(
                            selected = calendarReminderOffsets,
                            onToggle = {},
                            languageManager = languageManager,
                            enabled = false
                        )
                    } else {
                        ReminderOffsetsPicker(
                            selected = reminderOffsets,
                            onToggle = { offset ->
                                if (offset in reminderOffsets) reminderOffsets.remove(offset) else reminderOffsets.add(offset)
                            },
                            languageManager = languageManager
                        )
                    }
                    if (!canScheduleExactAlarms) {
                        Column {
                            Text(
                                languageManager.getString("reminder_exact_alarm_permission_needed"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(
                                onClick = {
                                    context.startActivity(
                                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                            .setData(Uri.fromParts("package", context.packageName, null))
                                    )
                                }
                            ) { Text(languageManager.getString("reminder_grant_permission")) }
                        }
                    }
                }
            }

            item {
                SectionCard {
                    SectionTitle(languageManager.getString("entry_tags"))
                    if (tags.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            tags.forEach { tag ->
                                InputChip(
                                    selected = false,
                                    onClick = { tags.remove(tag) },
                                    label = { Text(tag) },
                                    trailingIcon = { Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.height(16.dp)) }
                                )
                            }
                        }
                    }
                    // Every tag already used on any other entry, filtered as the user types —
                    // focusing the (possibly empty) field surfaces the full list so existing tags
                    // are reusable/discoverable instead of retyped (and risking near-duplicates).
                    // Rendered as a card that expands directly above the input — not a popup menu —
                    // so it reads as the field itself growing upward, and stays open across multiple
                    // taps (no per-tap collapse) so several tags can be picked in one go.
                    val tagSuggestions = availableTags.filter {
                        it !in tags && (tagInput.isBlank() || it.contains(tagInput, ignoreCase = true))
                    }
                    AnimatedVisibility(visible = tagMenuExpanded && tagSuggestions.isNotEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            FlowRow(
                                modifier = Modifier.padding(10.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                tagSuggestions.forEach { suggestion ->
                                    InputChip(
                                        selected = false,
                                        onClick = {
                                            tags.add(suggestion)
                                            tagInput = ""
                                        },
                                        label = { Text(suggestion) }
                                    )
                                }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = tagInput,
                            onValueChange = {
                                tagInput = it
                                tagMenuExpanded = true
                            },
                            label = { Text(languageManager.getString("entry_add_tag")) },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .onFocusChanged { tagMenuExpanded = it.isFocused }
                        )
                        IconButton(onClick = {
                            val clean = tagInput.trim()
                            if (clean.isNotEmpty() && clean !in tags) tags.add(clean)
                            tagInput = ""
                        }) {
                            Icon(Icons.Filled.Add, contentDescription = languageManager.getString("entry_add_tag"))
                        }
                    }
                }
            }

        }
    }

    if (showStartDatePicker) {
        DateOnlyPickerDialog(
            initialMillis = startMillis,
            onDismiss = { showStartDatePicker = false },
            onConfirm = { millis ->
                startMillis = combineDate(millis, startMillis, zoneId)
                showStartDatePicker = false
            },
            languageManager = languageManager
        )
    }
    if (showStartTimePicker) {
        TimeOnlyPickerDialog(
            initialMillis = startMillis,
            onDismiss = { showStartTimePicker = false },
            onConfirm = { millis ->
                startMillis = millis
                isStartTimeSetManually = true
                showStartTimePicker = false
            },
            languageManager = languageManager,
            defaultToZeroMinutes = existing == null && !isStartTimeSetManually
        )
    }
    if (showEndDatePicker) {
        DateOnlyPickerDialog(
            initialMillis = endMillis ?: startMillis,
            onDismiss = { showEndDatePicker = false },
            onConfirm = { millis ->
                endMillis = combineDate(millis, endMillis ?: startMillis, zoneId)
                showEndDatePicker = false
            },
            languageManager = languageManager
        )
    }
    if (showEndTimePicker) {
        TimeOnlyPickerDialog(
            initialMillis = endMillis ?: startMillis,
            onDismiss = { showEndTimePicker = false },
            onConfirm = { millis ->
                endMillis = millis
                isEndTimeSetManually = true
                showEndTimePicker = false
            },
            languageManager = languageManager,
            defaultToZeroMinutes = existing == null && !isEndTimeSetManually
        )
    }
    if (showUntilDatePicker) {
        DateOnlyPickerDialog(
            initialMillis = recurrenceUntilMillis ?: startMillis,
            onDismiss = { showUntilDatePicker = false },
            onConfirm = { millis ->
                recurrenceUntilMillis = millis
                showUntilDatePicker = false
            },
            languageManager = languageManager
        )
    }

    pendingCleanup?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingCleanup = null },
            title = { Text(languageManager.getString("cleanup_confirm_title")) },
            text = {
                Column {
                    Text(languageManager.getString("cleanup_confirm_message"))
                    Spacer(Modifier.height(8.dp))
                    pending.dirtyFields.forEach { field ->
                        Text(
                            buildAnnotatedString {
                                append("${entryFieldLabel(languageManager, field.fieldKey)}: ")
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
                        saveEntry(CalendarEntrySanitizer.sanitize(pending.entry), pending.tags)
                        pendingCleanup = null
                        onDone()
                    }
                ) { Text(languageManager.getString("auto_clean")) }
            },
            dismissButton = {
                TextButton(onClick = { pendingCleanup = null }) { Text(languageManager.getString("cancel")) }
            }
        )
    }

    if (showDeleteConfirm && existing != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(languageManager.getString("delete_entry_title")) },
            text = { Text(languageManager.getString("delete_entry_message")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        stateManager.deleteEntry(existing.entry)
                        showDeleteConfirm = false
                        onDone()
                    }
                ) { Text(languageManager.getString("delete")) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(languageManager.getString("cancel")) }
            }
        )
    }
}

private fun recurrenceLabelKey(freq: RecurrenceFrequency): String = when (freq) {
    RecurrenceFrequency.NONE -> "recurrence_none"
    RecurrenceFrequency.DAILY -> "recurrence_daily"
    RecurrenceFrequency.WEEKLY -> "recurrence_weekly"
    RecurrenceFrequency.MONTHLY -> "recurrence_monthly"
    RecurrenceFrequency.YEARLY -> "recurrence_yearly"
}

/** Unit noun for the "every [N] ___" interval stepper, distinct from [recurrenceLabelKey]'s adjective
 *  forms ("Daily") since this needs a plural noun regardless of N ("days", not "daily"). */
private fun recurrenceIntervalUnitKey(freq: RecurrenceFrequency): String = when (freq) {
    RecurrenceFrequency.NONE -> "recurrence_none"
    RecurrenceFrequency.DAILY -> "recurrence_unit_days"
    RecurrenceFrequency.WEEKLY -> "recurrence_unit_weeks"
    RecurrenceFrequency.MONTHLY -> "recurrence_unit_months"
    RecurrenceFrequency.YEARLY -> "recurrence_unit_years"
}

/** An Event's end time can't be before its start time — equal is fine (a zero-duration event), only
 *  strictly earlier is invalid. Tasks have no end time to validate. Pulled out as a pure function so
 *  it's unit-testable without a Composable. */
internal fun isTimeRangeInvalid(type: CalendarEntryType, startMillis: Long, endMillis: Long?): Boolean =
    type == CalendarEntryType.EVENT && endMillis?.let { startMillis > it } == true

private fun startOfDay(millis: Long, zoneId: ZoneId): Long =
    Instant.ofEpochMilli(millis).atZone(zoneId).toLocalDate().atStartOfDay(zoneId).toInstant().toEpochMilli()

/** Replaces [target]'s date portion with [dateMillis]'s date, keeping [target]'s time-of-day. */
private fun combineDate(dateMillis: Long, target: Long, zoneId: ZoneId): Long {
    val date = Instant.ofEpochMilli(dateMillis).atZone(zoneId).toLocalDate()
    val time = Instant.ofEpochMilli(target).atZone(zoneId).toLocalTime()
    return ZonedDateTime.of(date, time, zoneId).toInstant().toEpochMilli()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateOnlyPickerDialog(
    initialMillis: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
    languageManager: com.voxapps.calendarapp.domain.localization.LanguageManager
) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { state.selectedDateMillis?.let(onConfirm) }) { Text(languageManager.getString("apply")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(languageManager.getString("cancel")) } }
    ) {
        DatePicker(state = state)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeOnlyPickerDialog(
    initialMillis: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
    languageManager: com.voxapps.calendarapp.domain.localization.LanguageManager,
    defaultToZeroMinutes: Boolean = false
) {
    val zoneId = ZoneId.systemDefault()
    val initialTime = remember(initialMillis) { Instant.ofEpochMilli(initialMillis).atZone(zoneId).toLocalTime() }
    val state = rememberTimePickerState(
        initialHour = initialTime.hour,
        initialMinute = if (defaultToZeroMinutes) 0 else initialTime.minute
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(languageManager.getString("entry_start_time")) },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = {
                val date = Instant.ofEpochMilli(initialMillis).atZone(zoneId).toLocalDate()
                val time = LocalTime.of(state.hour, state.minute)
                onConfirm(ZonedDateTime.of(date, time, zoneId).toInstant().toEpochMilli())
            }) { Text(languageManager.getString("apply")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(languageManager.getString("cancel")) } }
    )
}

/** Manually-added photo attachments on this entry (see :core:attachments) — no scan-sourced photo
 *  concept here to unify with (unlike Expenses), so this is manual-only. */
@Composable
private fun EntryAttachmentsSection(entryId: Long, stateManager: CalendarStateManager) {
    val languageManager = LocalLanguageManager.current
    val context = LocalContext.current
    val entities by stateManager.observeAttachments(entryId).collectAsStateWithLifecycle(initialValue = emptyList())
    val items = remember(entities) {
        val groupSizes = entities.mapNotNull { it.groupId }.groupingBy { it }.eachCount()
        entities.map { e ->
            val groupSize = e.groupId?.let { groupSizes[it] } ?: 0
            AttachmentUiItem(
                id = e.id,
                uri = AttachmentFileStore.uriFor(context, CalendarAttachments.FILE_PROVIDER_AUTHORITY, CalendarAttachments.DIR, e.fileName),
                removable = true,
                groupLabel = if (groupSize > 1) "${e.groupOrder + 1}/$groupSize" else null,
                groupKey = e.groupId,
                groupSource = e.source
            )
        }
    }
    fun handlePickedUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val groupId = if (uris.size > 1) UUID.randomUUID().toString() else null
        uris.forEachIndexed { index, uri ->
            AttachmentFileStore.stage(context, uri, CalendarAttachments.DIR)?.let { fileName ->
                stateManager.addManualAttachment(entryId, fileName, groupId, index)
            }
        }
    }
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris -> handlePickedUris(uris) }
    // Zero attachments yet: single + stitch (a stitch group is one document, whole-group delete
    // only — see AttachmentUiItem.groupSource). Already has attachments: single + batch, each new
    // photo independent (Calendar never runs OCR/LLM on attachments at all — see LlmTasks.
    // CALENDAR_ATTACHMENT_CAPTURE's doc comment, so "batch" here differs from Expenses' only in that
    // there's no rescan-suggestion step, just independent staging).
    val takePhotoSingle = rememberVisionCaptureLauncher(
        baseTask = "${LlmTasks.CALENDAR_ATTACHMENT_CAPTURE}:$entryId", hint = null, produceOCR = false,
        captureMode = VoxOcrRequest.CAPTURE_MODE_SINGLE
    )
    val takePhotoStitch = rememberVisionCaptureLauncher(
        baseTask = "${LlmTasks.CALENDAR_ATTACHMENT_CAPTURE}:$entryId", hint = null, produceOCR = false,
        captureMode = VoxOcrRequest.CAPTURE_MODE_STITCH
    )
    val takePhotoBatch = rememberVisionCaptureLauncher(
        baseTask = "${LlmTasks.CALENDAR_ATTACHMENT_CAPTURE}:$entryId", hint = null, produceOCR = false,
        captureMode = VoxOcrRequest.CAPTURE_MODE_BATCH
    )
    val captureActions = if (items.isEmpty()) {
        listOf(
            SpeedDialAction(Icons.Filled.PhotoCamera, languageManager.getString("capture_mode_single"), takePhotoSingle),
            SpeedDialAction(Icons.Filled.Layers, languageManager.getString("capture_mode_stitch"), takePhotoStitch)
        )
    } else {
        listOf(
            SpeedDialAction(Icons.Filled.PhotoCamera, languageManager.getString("capture_mode_single"), takePhotoSingle),
            SpeedDialAction(Icons.Filled.BurstMode, languageManager.getString("capture_mode_batch"), takePhotoBatch)
        )
    }
    AttachmentsSection(
        title = languageManager.getString("attachments"),
        items = items,
        canAdd = items.size < 10,
        onPickFromGallery = { pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        captureActions = captureActions,
        galleryLabel = languageManager.getString("attachment_choose_gallery"),
        cancelLabel = languageManager.getString("cancel"),
        onRemove = { item ->
            entities.firstOrNull { it.id == item.id }?.let { stateManager.removeAttachment(it, context) }
        },
        groupDelete = GroupDeleteConfig(
            onDeleteGroup = { groupId -> stateManager.deleteAttachmentGroup(entryId, groupId, context) },
            confirmTitle = languageManager.getString("delete_attachment_group_title"),
            confirmMessage = languageManager.getString("delete_attachment_group_message"),
            confirmLabel = languageManager.getString("delete"),
            cancelLabel = languageManager.getString("cancel")
        )
    )
}

/** Attachments UI for a not-yet-saved entry: stages picked photos into this app's files dir via
 *  [AttachmentFileStore] immediately (no entry id needed for that), but only tracks them as local
 *  filenames — no [com.voxapps.attachments.AttachmentEntity] row exists until [EntryEditScreen] links
 *  them once the entry is actually saved (or deletes the staged files if the draft is discarded
 *  instead). A fake id derived from the filename's hash is enough for [AttachmentsSection]'s list
 *  key — it never needs a real database id. */
@Composable
private fun PendingEntryAttachmentsSection(pendingAttachments: List<String>, onChange: (List<String>) -> Unit) {
    val languageManager = LocalLanguageManager.current
    val context = LocalContext.current
    val items = remember(pendingAttachments) {
        pendingAttachments.map { fileName ->
            AttachmentUiItem(
                id = fileName.hashCode().toLong(),
                uri = AttachmentFileStore.uriFor(context, CalendarAttachments.FILE_PROVIDER_AUTHORITY, CalendarAttachments.DIR, fileName),
                removable = true
            )
        }
    }
    fun handlePickedUri(uri: Uri?) {
        if (uri != null) {
            AttachmentFileStore.stage(context, uri, CalendarAttachments.DIR)?.let { fileName ->
                onChange(pendingAttachments + fileName)
            }
        }
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> handlePickedUri(uri) }
    val takePhoto = rememberCameraCaptureLauncher(CalendarAttachments.FILE_PROVIDER_AUTHORITY) { uri -> handlePickedUri(uri) }
    AttachmentsSection(
        title = languageManager.getString("attachments"),
        items = items,
        canAdd = pendingAttachments.size < 10,
        onPickFromGallery = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        // Draft (not-yet-saved) entry attachments still use the plain system camera, not Vision —
        // out of scope for the single/stitch/batch speed dial (no OCR/record concept exists yet for
        // a draft), so this is just the one existing "take photo" action, unchanged behavior.
        captureActions = listOf(SpeedDialAction(Icons.Filled.PhotoCamera, languageManager.getString("attachment_take_photo"), takePhoto)),
        galleryLabel = languageManager.getString("attachment_choose_gallery"),
        cancelLabel = languageManager.getString("cancel"),
        onRemove = { item ->
            pendingAttachments.firstOrNull { it.hashCode().toLong() == item.id }?.let { fileName ->
                AttachmentFileStore.delete(context, CalendarAttachments.DIR, fileName)
                onChange(pendingAttachments - fileName)
            }
        }
    )
}

// --- "Paper form" shared style (mirrors vox-expenses' ExpenseEditScreen private composables) ---

@Composable
private fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun PaperField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    singleLine: Boolean = true,
    minLines: Int = 1,
    valueColor: Color = MaterialTheme.colorScheme.primary,
    dividerColor: Color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
    enabled: Boolean = true
) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
            if (value.isEmpty()) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                readOnly = !enabled,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = if (enabled) valueColor else valueColor.copy(alpha = 0.5f)),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = keyboardOptions,
                singleLine = singleLine,
                minLines = minLines,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = dividerColor, thickness = 1.dp)
    }
}

