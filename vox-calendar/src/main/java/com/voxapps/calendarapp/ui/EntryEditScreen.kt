package com.voxapps.calendarapp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.voxapps.calendarapp.data.CalendarEntry
import com.voxapps.calendarapp.data.CalendarEntrySanitizer
import com.voxapps.calendarapp.data.CalendarEntryType
import com.voxapps.calendarapp.data.CalendarEntryWithTags
import com.voxapps.calendarapp.data.CalendarLayer
import com.voxapps.calendarapp.data.RecurrenceFrequency
import com.voxapps.calendarapp.domain.localization.LanguageManager
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

private val OffenseRed = Color(0xFFD32F2F)

private data class PendingCleanup(val entry: CalendarEntry, val tags: List<String>, val dirtyFields: List<DirtyField>)

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
    stateManager: CalendarStateManager,
    onDone: () -> Unit
) {
    val languageManager = LocalLanguageManager.current
    val zoneId = ZoneId.systemDefault()

    var type by remember { mutableStateOf(existing?.entry?.type ?: CalendarEntryType.EVENT) }
    var title by remember { mutableStateOf(existing?.entry?.title ?: "") }
    var description by remember { mutableStateOf(existing?.entry?.description ?: "") }
    var location by remember { mutableStateOf(existing?.entry?.location ?: "") }
    var startMillis by remember { mutableStateOf(existing?.entry?.startMillis ?: System.currentTimeMillis()) }
    var endMillis by remember { mutableStateOf(existing?.entry?.endMillis) }
    var allDay by remember { mutableStateOf(existing?.entry?.allDay ?: false) }
    var completed by remember { mutableStateOf(existing?.entry?.completed ?: false) }
    var recurrence by remember { mutableStateOf(existing?.entry?.recurrenceFrequency ?: RecurrenceFrequency.NONE) }
    var recurrenceUntilMillis by remember { mutableStateOf(existing?.entry?.recurrenceUntilMillis) }
    val tags = remember { mutableStateListOf<String>().apply { addAll(existing?.tagNames ?: emptyList()) } }
    var tagInput by remember { mutableStateOf("") }

    var showStartDatePicker by remember { mutableStateOf(false) }
    var showStartTimePicker by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }
    var showEndTimePicker by remember { mutableStateOf(false) }
    var showUntilDatePicker by remember { mutableStateOf(false) }
    var recurrenceMenuExpanded by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var pendingCleanup by remember { mutableStateOf<PendingCleanup?>(null) }

    fun saveEntry(entry: CalendarEntry, entryTags: List<String>) {
        if (existing != null) {
            stateManager.updateEntry(entry, entryTags)
        } else {
            stateManager.addEntry(
                type = entry.type,
                title = entry.title,
                description = entry.description,
                location = entry.location,
                startMillis = entry.startMillis,
                endMillis = entry.endMillis,
                allDay = entry.allDay,
                completed = entry.completed,
                recurrenceFrequency = entry.recurrenceFrequency,
                recurrenceUntilMillis = entry.recurrenceUntilMillis,
                layerId = entry.layerId,
                tags = entryTags
            )
        }
    }

    // Shared by the checkmark button, the back arrow, and the system back gesture/button — this
    // screen has no separate "discard changes" path, so leaving it any way always tries to save
    // first. A blank title (the one mandatory field) has nothing meaningful to save, so that case
    // just closes without writing anything, matching the old Save button's `enabled = title.isNotBlank()`
    // guard instead of silently creating an empty-titled entry.
    fun attemptSaveAndClose() {
        if (title.isBlank()) {
            onDone()
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
            recurrenceFrequency = recurrence,
            recurrenceUntilMillis = recurrenceUntilMillis
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
                            Text(
                                languageManager.getString("entry_title"),
                                style = MaterialTheme.typography.titleLarge,
                                color = LocalContentColor.current.copy(alpha = 0.5f)
                            )
                        }
                        BasicTextField(
                            value = title,
                            onValueChange = { title = it },
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
                    if (existing != null) {
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = languageManager.getString("delete"))
                        }
                    }
                    IconButton(onClick = ::attemptSaveAndClose, enabled = title.isNotBlank()) {
                        Icon(Icons.Filled.Check, contentDescription = languageManager.getString("save"))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = type == CalendarEntryType.EVENT,
                        onClick = { type = CalendarEntryType.EVENT },
                        label = { Text(languageManager.getString("entry_type_event")) }
                    )
                    FilterChip(
                        selected = type == CalendarEntryType.TASK,
                        onClick = { type = CalendarEntryType.TASK },
                        label = { Text(languageManager.getString("entry_type_task")) }
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
                        minLines = 2
                    )
                    PaperField(label = languageManager.getString("entry_location"), value = location, onValueChange = { location = it })
                    Text(
                        text = "${languageManager.getString("entry_layer")}: ${defaultLayer.name}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                            modifier = Modifier.weight(1f)
                        )
                        if (!allDay) {
                            PaperTapField(
                                label = languageManager.getString("entry_start_time"),
                                value = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(startMillis)),
                                onClick = { showStartTimePicker = true },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    if (type == CalendarEntryType.EVENT) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = allDay, onCheckedChange = { checked ->
                                allDay = checked
                                if (checked) {
                                    startMillis = startOfDay(startMillis, zoneId)
                                    endMillis = endMillis?.let { startOfDay(it, zoneId) }
                                }
                            })
                            Text(languageManager.getString("entry_all_day"))
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                            PaperTapField(
                                label = languageManager.getString("entry_end_date"),
                                value = endMillis?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) }
                                    ?: languageManager.getString("none"),
                                onClick = { showEndDatePicker = true },
                                modifier = Modifier.weight(1f)
                            )
                            if (!allDay) {
                                PaperTapField(
                                    label = languageManager.getString("entry_end_time"),
                                    value = endMillis?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) }
                                        ?: languageManager.getString("none"),
                                    onClick = { if (endMillis == null) endMillis = startMillis; showEndTimePicker = true },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    } else {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = completed, onCheckedChange = { completed = it })
                            Text(languageManager.getString("entry_completed"))
                        }
                    }

                    Box {
                        PaperTapField(
                            label = languageManager.getString("entry_recurrence"),
                            value = languageManager.getString(recurrenceLabelKey(recurrence)),
                            onClick = { recurrenceMenuExpanded = true },
                            trailingIcon = {
                                Icon(Icons.Filled.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        )
                        DropdownMenu(expanded = recurrenceMenuExpanded, onDismissRequest = { recurrenceMenuExpanded = false }) {
                            RecurrenceFrequency.entries.forEach { freq ->
                                DropdownMenuItem(
                                    text = { Text(languageManager.getString(recurrenceLabelKey(freq))) },
                                    onClick = {
                                        recurrence = freq
                                        if (freq == RecurrenceFrequency.NONE) recurrenceUntilMillis = null
                                        recurrenceMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    if (recurrence != RecurrenceFrequency.NONE) {
                        PaperTapField(
                            label = languageManager.getString("entry_recurrence_until"),
                            value = recurrenceUntilMillis?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) }
                                ?: languageManager.getString("entry_recurrence_forever"),
                            onClick = { showUntilDatePicker = true }
                        )
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
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = tagInput,
                            onValueChange = { tagInput = it },
                            label = { Text(languageManager.getString("entry_add_tag")) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
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
                showStartTimePicker = false
            },
            languageManager = languageManager
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
                showEndTimePicker = false
            },
            languageManager = languageManager
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
    languageManager: com.voxapps.calendarapp.domain.localization.LanguageManager
) {
    val zoneId = ZoneId.systemDefault()
    val initialTime = remember(initialMillis) { Instant.ofEpochMilli(initialMillis).atZone(zoneId).toLocalTime() }
    val state = rememberTimePickerState(initialHour = initialTime.hour, initialMinute = initialTime.minute)
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
    dividerColor: Color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
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
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = valueColor),
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

@Composable
private fun PaperTapField(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailingIcon: @Composable () -> Unit = {}
) {
    Column(modifier = modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
        ) {
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            trailingIcon()
        }
        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), thickness = 1.dp)
    }
}
