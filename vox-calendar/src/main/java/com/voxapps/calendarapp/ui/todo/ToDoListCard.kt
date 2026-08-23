package com.voxapps.calendarapp.ui.todo

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voxapps.calendarapp.CalendarApplication
import com.voxapps.calendarapp.data.WeekdayMask
import com.voxapps.calendarapp.data.ToDoItem
import com.voxapps.calendarapp.data.ToDoList
import com.voxapps.calendarapp.data.ToDoRepository
import com.voxapps.calendarapp.ui.EntryAttachmentsSection
import com.voxapps.calendarapp.ui.WeekdayPickerRow
import com.voxapps.calendarapp.ui.weekdayLabelKey
import com.voxapps.calendarapp.ui.LocalLanguageManager
import com.voxapps.design.color.VoxColorPalette
import com.voxapps.design.color.VoxColorSwatchPicker
import com.voxapps.design.color.VoxCustomColorDialog
import com.voxapps.design.openLocationInMaps
import com.voxapps.location.EphemeralLocationStore
import com.voxapps.location.VoxLocationResolver
import com.voxapps.location.ui.VoxLocationField
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Date
import com.voxapps.design.color.VoxSwatchShapes
import androidx.compose.foundation.gestures.Orientation
import com.voxapps.design.VoxSemanticColors

/** Fixed amber tint for the "important" star — same fixed-color-regardless-of-item-hue treatment as
 *  [DONE_CHECK_COLOR] in ToDoNodeTimeline.kt. */
private val IMPORTANT_STAR_COLOR = VoxSemanticColors.important

/** Colors for the tappable "important" toggle star in [TaskEditDialog] — bright red when active,
 *  a darker red outline glyph when inactive, distinct from [IMPORTANT_STAR_COLOR]'s amber (which
 *  marks already-important items elsewhere) since this is the control that sets the flag. */
private val IMPORTANT_TOGGLE_ON_COLOR = VoxSemanticColors.importantToggleOn
private val IMPORTANT_TOGGLE_OFF_COLOR = VoxSemanticColors.importantToggleOff

/** Same fixed green as [ToDoNodeTimeline]'s own `DONE_CHECK_COLOR` (duplicated here rather than
 *  exported — same "fixed-color-regardless-of-item-hue" constant, same cross-file duplication
 *  convention [IMPORTANT_STAR_COLOR] above already follows) — the "Done" toggle uses the exact same
 *  green a done item's node/chip check icon already uses elsewhere, on or off. */
private val DONE_CHECK_COLOR = VoxSemanticColors.done

/** offsetMinutesBefore -> translation key, identical preset set to EntryEditScreen's event reminders
 *  so setting a reminder on a checklist item's due date feels the same as setting one on an event. */
private val TODO_REMINDER_PRESETS = listOf(
    0 to "reminder_at_start",
    5 to "reminder_5min",
    15 to "reminder_15min",
    30 to "reminder_30min",
    60 to "reminder_1hour",
    1440 to "reminder_1day"
)

/**
 * One [ToDoList] presented as a Notes-card-style card with two faces, switched via a real horizontal
 * 3D flip (`graphicsLayer.rotationY`, content swapped at the 90° midpoint so neither face ever renders
 * mirrored): a view face (tap a node to toggle done, tap anywhere else on the card to flip into edit)
 * and an edit face (drag-reorderable node timeline + per-task editing + a vertical color-swatch strip
 * for the list's own color, mirroring vox-notes' `CategoryCoverflow` positioning).
 */
@Composable
fun ToDoListCard(
    list: ToDoList,
    items: List<ToDoItem>,
    toDoRepository: ToDoRepository,
    isEditing: Boolean,
    onEditingChange: (Boolean) -> Unit,
    onDeleteList: () -> Unit,
    modifier: Modifier = Modifier,
    // Widget deep-link: open this item's inline editor (trigger-counter convention, see
    // CalendarActivity's editEntryTrigger).
    openTaskId: Long = -1L,
    openTaskTrigger: Int = 0
) {
    val scope = rememberCoroutineScope()
    val cardContext = LocalContext.current
    // Editor expand/collapse animation honors the theme menu's animations toggle.
    val cardSettings by (cardContext.applicationContext as CalendarApplication).container
        .settingsRepository.settingsFlow.collectAsState(initial = null)
    val animateEditor = cardSettings?.animationsEnabled == true
    // One open inline editor per card, shared by both faces — opening another node saves and closes
    // the already-open one (the editor commits on dispose).
    var editingTaskId by remember(list.id) { mutableStateOf<Long?>(null) }
    LaunchedEffect(openTaskTrigger) {
        if (openTaskTrigger > 0 && openTaskId >= 0) editingTaskId = openTaskId
    }
    // Saving/closing the LIST (header ✓, header tap, back) also saves and closes the open item
    // editor: dropping the id removes the editor from composition, and its dispose-commit runs.
    // TRANSITION-only — running on first composition (isEditing starts false) would null the id a
    // widget/grid deep-link just set.
    var wasEditing by remember(list.id) { mutableStateOf(isEditing) }
    LaunchedEffect(isEditing) {
        if (wasEditing && !isEditing) editingTaskId = null
        wasEditing = isEditing
    }
    val rotation by animateFloatAsState(if (isEditing) 180f else 0f, animationSpec = tween(450), label = "todoCardFlip")
    val density = LocalDensity.current
    // The exact same contour-vs-fill derivation every pill and node uses (toneBorderColor) — one
    // contrast rule across cards, pills, stars, and circles.
    val editBorderColor = remember(list.colorArgb) { toneBorderColor(Color(list.colorArgb.toInt())) }
    // The whole card's edit-mode contour breathes exactly like an open inline item editor —
    // "this card has pending edits" — and stills when the card flips back. Same animations gate.
    val cardPulseAlpha: Float = if (isEditing && animateEditor) {
        val pulse = rememberInfiniteTransition(label = "cardPendingPulse")
        val alpha by pulse.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "cardPendingPulseAlpha"
        )
        alpha
    } else 1f

    Card(
        modifier = modifier
            .fillMaxWidth()
            // Every card wears its border in both faces — the edit face is the one that breathes.
            .border(2.dp, editBorderColor.copy(alpha = if (isEditing) cardPulseAlpha else 1f), CardDefaults.shape)
            .graphicsLayer {
                rotationY = rotation
                cameraDistance = 12f * density.density
            },
        colors = CardDefaults.cardColors(containerColor = Color(list.colorArgb.toInt()).copy(alpha = 0.16f))
    ) {
        if (rotation <= 90f) {
            ToDoListViewFace(
                list = list,
                items = items,
                toDoRepository = toDoRepository,
                scope = scope,
                onToggleDone = { item -> scope.launch { toDoRepository.toggleDone(item) } },
                onEnterEdit = { onEditingChange(true) },
                editingTaskId = editingTaskId,
                onEditTask = { editingTaskId = it },
                animateEditor = animateEditor
            )
        } else {
            Box(Modifier.graphicsLayer { rotationY = 180f }) {
                ToDoListEditFace(
                    list = list,
                    items = items,
                    toDoRepository = toDoRepository,
                    scope = scope,
                    onDone = { onEditingChange(false) },
                    onDeleteList = onDeleteList,
                    editingTaskId = editingTaskId,
                    onEditTask = { editingTaskId = it },
                    animateEditor = animateEditor
                )
            }
        }
    }
}

@Composable
private fun ToDoListViewFace(
    list: ToDoList,
    items: List<ToDoItem>,
    toDoRepository: ToDoRepository,
    scope: CoroutineScope,
    onToggleDone: (ToDoItem) -> Unit,
    onEnterEdit: () -> Unit,
    editingTaskId: Long?,
    onEditTask: (Long?) -> Unit,
    animateEditor: Boolean
) {
    val languageManager = LocalLanguageManager.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEnterEdit)
            .padding(16.dp)
    ) {
        Text(list.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        if (items.isEmpty()) {
            Text(
                languageManager.getString("todo_list_empty"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            ToDoNodeTimeline(
                items = items,
                isEditing = false,
                onToggleDone = onToggleDone,
                // Tapping a chip opens the item's INLINE editor right below its row — the timeline
                // shifts down around it rather than a modal covering the list.
                onTaskClick = { item -> onEditTask(item.id) },
                onAddAtStart = {},
                onAddAtEnd = {},
                animateEditor = animateEditor,
                editingItemId = editingTaskId,
                editor = { item ->
                    TaskEditInlineCard(
                        item = item,
                        list = list,
                        toDoRepository = toDoRepository,
                        scope = scope,
                        onClose = { onEditTask(null) }
                    )
                }
            )
        }
    }
}

@Composable
private fun ToDoListEditFace(
    list: ToDoList,
    items: List<ToDoItem>,
    toDoRepository: ToDoRepository,
    scope: CoroutineScope,
    onDone: () -> Unit,
    onDeleteList: () -> Unit,
    editingTaskId: Long?,
    onEditTask: (Long?) -> Unit,
    animateEditor: Boolean
) {
    val languageManager = LocalLanguageManager.current
    val context = LocalContext.current
    var title by remember(list.id) { mutableStateOf(list.title) }
    var quickEditItem by remember { mutableStateOf<ToDoItem?>(null) }
    var showDeleteListConfirm by remember { mutableStateOf(false) }
    // Hoisted here (rather than inside VerticalColorStrip) so it survives the Room Flow round-trip
    // that updateListColor triggers on every tap — that write recomposes this whole subtree with a
    // fresh `list`, and a `remember` living further down was observed re-collapsing as a result.
    var listColorExpanded by remember(list.id) { mutableStateOf(false) }
    // Same retracted-until-tapped convention as the color strip: the routine lives behind one
    // header icon (gray = not a routine), and its day chips only occupy the card while open.
    var routineExpanded by remember(list.id) { mutableStateOf(false) }

    fun commitTitle() {
        val trimmed = title.trim()
        if (trimmed.isNotEmpty() && trimmed != list.title) scope.launch { toDoRepository.renameList(list, trimmed) }
    }

    fun commitAndClose() {
        commitTitle()
        onDone()
    }

    // The edit face can be flipped away by MORE than its own ✓ — the screen's header tap, the top
    // bar's back arrow, the system back — and a title typed but not yet committed must survive
    // every one of them. Committing on dispose covers them all; [scope] is the CARD's (not this
    // face's), so it is still alive to run the write after the flip. The ✓ path commits the same
    // value first, making this a no-op there.
    DisposableEffect(list.id) {
        onDispose { commitTitle() }
    }

    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = ::commitAndClose) {
                Icon(Icons.Filled.Check, contentDescription = languageManager.getString("apply"))
            }
            BasicTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { inner ->
                    if (title.isEmpty()) {
                        Text(
                            languageManager.getString("todo_new_list_hint"),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    inner()
                },
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { routineExpanded = !routineExpanded }) {
                Icon(
                    Icons.Filled.EventRepeat,
                    contentDescription = languageManager.getString("todo_routine_label"),
                    tint = if (list.routineDaysMask != 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            Box(modifier = Modifier.width(EDIT_FACE_RIGHT_COLUMN_WIDTH), contentAlignment = Alignment.Center) {
                IconButton(onClick = { showDeleteListConfirm = true }, modifier = Modifier.size(EDIT_FACE_RIGHT_COLUMN_WIDTH)) {
                    Icon(Icons.Filled.Delete, contentDescription = languageManager.getString("delete"))
                }
            }
        }
        if (routineExpanded) {
            Text(
                languageManager.getString("todo_routine_hint"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            WeekdayPickerRow(
                mask = list.routineDaysMask,
                onToggle = { day ->
                    scope.launch {
                        toDoRepository.updateListRoutineDays(list, WeekdayMask.toggled(list.routineDaysMask, day))
                    }
                },
                labels = { day -> languageManager.getString(weekdayLabelKey(day)) },
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            ToDoNodeTimelineEditable(
                items = items,
                onToggleDone = { item -> scope.launch { toDoRepository.toggleDone(item) } },
                onTaskClick = { item -> onEditTask(item.id) },
                onAddAt = { position ->
                    scope.launch {
                        // A fresh node goes straight into its inline editor with the title focused —
                        // adding and then hunting for the blank chip to name it was two taps for
                        // what is one intention.
                        val newId = toDoRepository.addItem(list.id, "", atPosition = position)
                        onEditTask(newId)
                    }
                },
                onReorderCommitted = { ordered -> scope.launch { toDoRepository.reorderItems(ordered) } },
                onDeleteItem = { item -> scope.launch { toDoRepository.deleteItem(item) } },
                onQuickEditDate = { item -> quickEditItem = item },
                modifier = Modifier.weight(1f),
                onCloseEditor = { onEditTask(null) },
                animateEditor = animateEditor,
                editingItemId = editingTaskId,
                editor = { item ->
                    TaskEditInlineCard(
                        item = item,
                        list = list,
                        toDoRepository = toDoRepository,
                        scope = scope,
                        onClose = { onEditTask(null) }
                    )
                }
            )
            Box(modifier = Modifier.width(EDIT_FACE_RIGHT_COLUMN_WIDTH), contentAlignment = Alignment.Center) {
                // The same picker every other colour choice uses, stood on its side: this card is
                // row-shaped, so its colours run down the trailing edge. It carried a copy of the
                // component to get that, which then missed everything the shared one gained.
                VoxColorSwatchPicker(
                    selectedColor = list.colorArgb,
                    onColorSelected = { color -> scope.launch { toDoRepository.updateListColor(list, color) } },
                    expanded = listColorExpanded,
                    onExpandedChange = { listColorExpanded = it },
                    orientation = Orientation.Vertical,
                    swatchSize = VERTICAL_SWATCH_SIZE,
                    modifier = Modifier.heightIn(max = 360.dp),
                    customColorDialogTitle = languageManager.getString("todo_custom_color_title"),
                    customColorUseLabel = languageManager.getString("apply"),
                    customColorCancelLabel = languageManager.getString("cancel"),
                    customColorHueLabel = languageManager.getString("todo_color_hue"),
                    customColorSaturationLabel = languageManager.getString("todo_color_saturation"),
                    customColorBrightnessLabel = languageManager.getString("todo_color_brightness")
                )
            }
        }
    }

    quickEditItem?.let { item ->
        DateTimeQuickEditDialog(
            initialDueMillis = item.dueMillis,
            onApply = { newMillis -> scope.launch { toDoRepository.setItemDueDate(item, newMillis, list) } },
            onDismiss = { quickEditItem = null }
        )
    }

    if (showDeleteListConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteListConfirm = false },
            title = { Text(languageManager.getString("todo_delete_list_title")) },
            text = { Text(languageManager.getString("todo_delete_list_message")) },
            confirmButton = {
                TextButton(onClick = { showDeleteListConfirm = false; onDeleteList() }) {
                    Text(languageManager.getString("delete"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteListConfirm = false }) { Text(languageManager.getString("cancel")) }
            }
        )
    }
}

/** Shared width for the edit face's whole right-edge column — the delete-list button up top and the
 *  color-picker strip below it both sit inside a box this wide, so their contents line up on the
 *  same vertical axis instead of drifting apart (48dp default IconButton touch target vs. the
 *  strip's own narrower width). */
private val EDIT_FACE_RIGHT_COLUMN_WIDTH = 40.dp

private val VERTICAL_SWATCH_SIZE = 28.dp
private val VERTICAL_SWATCH_PEEK_OFFSET = 10.dp
private val VERTICAL_SWATCH_PEEK_ALPHAS = listOf(0.75f, 0.5f, 0.3f)

@OptIn(ExperimentalMaterial3Api::class)
/** Public so [com.voxapps.calendarapp.ui.CalendarRoot] can reuse it directly when a tapped
 *  calendar-grid entry turns out to be to-do-flavored ([com.voxapps.calendarapp.data.CalendarEntry
 *  .listId] != null) — same dialog, same edit surface, whichever screen it was opened from. */
@Composable
fun TaskEditInlineCard(
    item: ToDoItem,
    list: ToDoList,
    toDoRepository: ToDoRepository,
    scope: CoroutineScope,
    onClose: () -> Unit
) {
    val languageManager = LocalLanguageManager.current
    val context = LocalContext.current
    var text by remember(item.id) { mutableStateOf(item.text) }
    var dueMillis by remember(item.id) { mutableStateOf(item.dueMillis) }
    var color by remember(item.id) { mutableStateOf(item.colorArgb) }
    var important by remember(item.id) { mutableStateOf(item.isImportant) }
    var reminderOffsets by remember(item.id) { mutableStateOf<List<Int>>(emptyList()) }
    var showQuickDateEdit by remember { mutableStateOf(false) }
    // Collapsed by default — the full preset list (6 chips) dwarfed the rest of the dialog's content
    // when it was always shown; now it's a single "Reminders" trigger that only expands once there's
    // actually a date to attach a reminder to.
    var remindersExpanded by remember(item.id) { mutableStateOf(false) }
    // Hoisted for the same reason as ToDoListEditFace's listColorExpanded — updateItemColor's Room
    // Flow round-trip recomposes the dialog's content with a fresh `item`, and a `remember` living
    // inside VoxColorSwatchPicker itself was observed re-collapsing as a result.
    var colorExpanded by remember(item.id) { mutableStateOf(false) }
    var itemLocation by remember(item.id) { mutableStateOf(item.location.orEmpty()) }
    val container = remember { (context.applicationContext as CalendarApplication).container }
    val stateManager = container.calendarStateManager
    // Explicit paths (done circle, Cancel, Apply) mark themselves so the dispose-time safety commit
    // below doesn't run a second time — commit() diffs against a snapshot that may not have
    // round-tripped yet, and a double toggleDone would flip the flag straight back.
    var closedExplicitly by remember(item.id) { mutableStateOf(false) }
    var showDeleteItemConfirm by remember(item.id) { mutableStateOf(false) }
    // A blank item is one the ghost-add just created for this dialog: land the cursor in the title
    // with the keyboard up, so naming it is typing, not a third tap. An existing item opens without
    // stealing focus — this dialog is also how color/date/done get tweaked.
    val titleFocus = remember { FocusRequester() }
    LaunchedEffect(item.id) { if (item.text.isEmpty()) titleFocus.requestFocus() }

    LaunchedEffect(item.id) { reminderOffsets = toDoRepository.getReminderOffsetsForItem(item) }

    fun commit() {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            // Title is mandatory. A freshly ghost-added item starts out blank (see GhostAddRow's
            // onClick, which inserts before the user ever types a name) — if it's left blank here,
            // drop it entirely rather than persisting a permanently nameless item. An EXISTING
            // (already-named) item that got cleared just keeps its old name instead — clearing the
            // whole field while only meaning to tweak color/date shouldn't silently delete it.
            if (item.text.isEmpty()) scope.launch { toDoRepository.deleteItem(item) }
        } else if (trimmed != item.text) {
            scope.launch { toDoRepository.updateItemText(item, trimmed) }
        }
        if (color != item.colorArgb) scope.launch { toDoRepository.updateItemColor(item, color) }
        if (dueMillis != item.dueMillis) scope.launch { toDoRepository.setItemDueDate(item, dueMillis, list) }
        if (important != item.isImportant) scope.launch { toDoRepository.updateItemImportant(item, important) }
        val trimmedLocation = itemLocation.trim().takeIf { it.isNotEmpty() }
        if (trimmedLocation != item.location) scope.launch { toDoRepository.updateItemLocation(item, trimmedLocation) }
    }

    // The inline card can leave composition by MORE than its own buttons — tapping another node
    // (spec: the open one saves, the other opens), flipping the card face, scrolling it out of the
    // lazy edit timeline — and typed edits must survive all of them.
    DisposableEffect(item.id) {
        onDispose { if (!closedExplicitly) commit() }
    }

    // The card sits IN the node's row, in place of the pill — its contour is the node's own color,
    // so it reads as that node, expanded. Done lives on the node circle beside it, not in here;
    // flipping it grays the card the way the timeline grays a done node (desaturated contour,
    // gray-leaning darker wash) so the palette edit visibly "takes".
    val itemToneColor = itemTone(item.colorArgb, item.done, item.isImportant)
    val contourColor = itemBorderColor(itemToneColor, item.done)
    // While the editor is open its contour breathes — "this node has unsaved state" — and stills
    // the moment it closes (saving closes it). Honors the theme menu's animations toggle.
    val settings by container.settingsRepository.settingsFlow.collectAsState(initial = null)
    val pulseAlpha: Float = if (settings?.animationsEnabled == true) {
        val pulse = rememberInfiniteTransition(label = "pendingPulse")
        val alpha by pulse.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "pendingPulseAlpha"
        )
        alpha
    } else 1f

    // A done card is filled flat rather than washed: the wash is a way of saying "this item, in the
    // background", and a finished item is not a background version of itself.
    val washColor = if (item.done) itemToneColor else itemToneColor.copy(alpha = 0.12f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            // A toned-out wash of the contour color — still clearly "this node", text stays legible.
            .background(washColor)
            .border(2.dp, contourColor.copy(alpha = contourColor.alpha * pulseAlpha), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { inner ->
                            if (text.isEmpty()) {
                                Text(
                                    languageManager.getString("todo_new_item_hint") + "…",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            inner()
                        },
                        modifier = Modifier.weight(1f).focusRequester(titleFocus)
                    )
                    // Cancel is the small ✕ in the card's top-right corner — the only control up
                    // here: the title needs no save affordance of its own, it commits with every
                    // other field (done shows on the node beside the card).
                    IconButton(
                        onClick = { closedExplicitly = true; onClose() },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = languageManager.getString("cancel"),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Column {
                    VoxColorSwatchPicker(
                        selectedColor = color,
                        onColorSelected = { color = it },
                        showSelectionRing = true,
                        // Important items are drawn as stars on the timeline; the selector says the
                        // same thing, and follows the toggle below as it is flipped.
                        shape = if (important) VoxSwatchShapes.Star else CircleShape,
                        expanded = colorExpanded,
                        onExpandedChange = { colorExpanded = it },
                        customColorDialogTitle = languageManager.getString("todo_custom_color_title"),
                        customColorUseLabel = languageManager.getString("apply"),
                        customColorCancelLabel = languageManager.getString("cancel"),
                        customColorHueLabel = languageManager.getString("todo_color_hue"),
                        customColorSaturationLabel = languageManager.getString("todo_color_saturation"),
                        customColorBrightnessLabel = languageManager.getString("todo_color_brightness")
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { important = !important }
                    ) {
                        Icon(
                            if (important) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = null,
                            tint = if (important) IMPORTANT_TOGGLE_ON_COLOR else IMPORTANT_TOGGLE_OFF_COLOR
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(languageManager.getString("todo_important_event"), modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { showQuickDateEdit = true }) {
                            Text(
                                dueMillis?.let {
                                    "${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it))} " +
                                        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))
                                } ?: languageManager.getString("todo_set_due_date")
                            )
                        }
                        if (dueMillis != null) {
                            IconButton(onClick = { dueMillis = null }) {
                                Icon(Icons.Filled.Close, contentDescription = languageManager.getString("todo_clear_due_date"))
                            }
                        }
                    }
                    VoxLocationField(
                        value = itemLocation,
                        onValueChange = { itemLocation = it },
                        label = languageManager.getString("todo_set_location"),
                        clearContentDescription = languageManager.getString("todo_clear_location"),
                        // Search-first entry (OpenStreetMap place search + GPS lock) — the GPS
                        // lambda resolves a FRESH fix through a store that remembers nothing:
                        // calendar keeps no location cache by design.
                        gpsLock = {
                            VoxLocationResolver.create(
                                context, EphemeralLocationStore(), needsReverseGeocode = true
                            ).resolveLocation()?.displayName
                        },
                        onOpenLocation = { openLocationInMaps(context, it) }
                    )
                    // A to-do item IS a CalendarEntry row, so the entry attachment section
                    // (shared :core:attachments UI) works on it unchanged — shown as its own card,
                    // no separate expander icon.
                    EntryAttachmentsSection(item.id, stateManager, singleCaptureOnly = true)
                    if (dueMillis != null) {
                        if (!remindersExpanded) {
                            TextButton(onClick = { remindersExpanded = true }) {
                                Text(
                                    if (reminderOffsets.isEmpty()) {
                                        languageManager.getString("entry_reminders")
                                    } else {
                                        "${languageManager.getString("entry_reminders")} (${reminderOffsets.size})"
                                    }
                                )
                            }
                        } else {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                TODO_REMINDER_PRESETS.forEach { (offset, labelKey) ->
                                    FilterChip(
                                        selected = offset in reminderOffsets,
                                        onClick = {
                                            val updated = if (offset in reminderOffsets) reminderOffsets - offset else reminderOffsets + offset
                                            reminderOffsets = updated
                                            scope.launch { toDoRepository.setItemReminders(item, updated) }
                                        },
                                        label = { Text(languageManager.getString(labelKey)) }
                                    )
                                }
                            }
                        }
                    }
                }
        // Delete lives bottom-LEFT, far from apply; the bold ✓ stays bottom-right. A never-saved
        // (still nameless) item has nothing to delete — abandoning it drops it on its own.
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (item.text.isNotEmpty()) {
                IconButton(onClick = { showDeleteItemConfirm = true }) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = languageManager.getString("delete"),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = { closedExplicitly = true; commit(); onClose() },
                enabled = text.isNotBlank()
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = languageManager.getString("apply"),
                    tint = if (text.isNotBlank()) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    },
                    modifier = Modifier.size(30.dp)
                )
            }
        }
    }

    if (showDeleteItemConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteItemConfirm = false },
            title = { Text(languageManager.getString("todo_delete_item_title")) },
            text = { Text(languageManager.getString("todo_delete_item_message")) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteItemConfirm = false
                    closedExplicitly = true
                    scope.launch { toDoRepository.deleteItem(item) }
                    onClose()
                }) {
                    Text(languageManager.getString("delete"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteItemConfirm = false }) { Text(languageManager.getString("cancel")) }
            }
        )
    }

    if (showQuickDateEdit) {
        DateTimeQuickEditDialog(
            initialDueMillis = dueMillis,
            onApply = { dueMillis = it },
            onDismiss = { showQuickDateEdit = false }
        )
    }
}

private enum class QuickDateEditStage { DATE, TIME }

/** Direct date-then-time picker chain shared by [TaskEditDialog]'s own date/time button and the
 *  edit-mode timeline's tap-to-quick-edit / drag-mismatch auto-open. No intermediate "hub" screen —
 *  that used to show its own AlertDialog with date/time buttons + Cancel/Apply before ever reaching
 *  the actual calendar/clock, which read as a redundant menu stop (and, after applying a date, landed
 *  back on that same hub instead of returning to the caller). This goes straight from the caller into
 *  [DatePickerDialog], then straight into the time picker, then straight back to the caller — Cancel
 *  at either step discards the whole edit rather than partially applying it. Clearing an existing due
 *  date entirely is handled by [TaskEditDialog]'s own "X" button, not duplicated here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimeQuickEditDialog(
    initialDueMillis: Long?,
    onApply: (Long?) -> Unit,
    onDismiss: () -> Unit
) {
    val languageManager = LocalLanguageManager.current
    val zoneId = remember { ZoneId.systemDefault() }
    var stage by remember { mutableStateOf(QuickDateEditStage.DATE) }
    var pickedDateMillis by remember { mutableStateOf<Long?>(null) }

    when (stage) {
        QuickDateEditStage.DATE -> {
            val state = rememberDatePickerState(initialSelectedDateMillis = initialDueMillis ?: System.currentTimeMillis())
            DatePickerDialog(
                onDismissRequest = onDismiss,
                confirmButton = {
                    TextButton(onClick = {
                        val picked = state.selectedDateMillis
                        if (picked == null) {
                            onDismiss()
                        } else {
                            pickedDateMillis = combineDateKeepingTime(picked, initialDueMillis ?: System.currentTimeMillis())
                            stage = QuickDateEditStage.TIME
                        }
                    }) { Text(languageManager.getString("apply")) }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text(languageManager.getString("cancel")) } }
            ) {
                DatePicker(state = state)
            }
        }

        QuickDateEditStage.TIME -> {
            val base = pickedDateMillis ?: initialDueMillis ?: System.currentTimeMillis()
            val initialTime = remember(base) { Instant.ofEpochMilli(base).atZone(zoneId).toLocalTime() }
            val state = rememberTimePickerState(initialHour = initialTime.hour, initialMinute = initialTime.minute)
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(languageManager.getString("todo_set_due_time")) },
                text = { TimePicker(state = state) },
                confirmButton = {
                    TextButton(onClick = {
                        val date = Instant.ofEpochMilli(base).atZone(zoneId).toLocalDate()
                        val finalMillis = ZonedDateTime.of(date, LocalTime.of(state.hour, state.minute), zoneId).toInstant().toEpochMilli()
                        onApply(finalMillis)
                        onDismiss()
                    }) { Text(languageManager.getString("apply")) }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text(languageManager.getString("cancel")) } }
            )
        }
    }
}

/** Replaces [target]'s date portion with [dateMillis]'s date, keeping [target]'s time-of-day —
 *  mirrors EntryEditScreen's identically-named private helper (not shared: that one is file-private). */
private fun combineDateKeepingTime(dateMillis: Long, target: Long, zoneId: ZoneId = ZoneId.systemDefault()): Long {
    val date = Instant.ofEpochMilli(dateMillis).atZone(zoneId).toLocalDate()
    val time = Instant.ofEpochMilli(target).atZone(zoneId).toLocalTime()
    return ZonedDateTime.of(date, time, zoneId).toInstant().toEpochMilli()
}
