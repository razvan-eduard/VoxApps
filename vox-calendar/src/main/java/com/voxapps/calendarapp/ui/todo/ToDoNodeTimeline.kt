package com.voxapps.calendarapp.ui.todo

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.voxapps.calendarapp.data.ToDoItem
import com.voxapps.calendarapp.ui.LocalLanguageManager
import com.voxapps.calendarapp.ui.nothingElseTodayEmojis
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import com.voxapps.calendar.rememberNowMillis
import com.voxapps.design.VoxSemanticColors
import com.voxapps.design.NowLine

private const val DELETE_ZONE_KEY = "delete-zone"

private val NODE_SIZE = 22.dp
private val NODE_SIZE_NEXT = 26.dp
private val GHOST_NODE_SIZE = 18.dp
// A star reads visually smaller than a circle of the same bounding box, so important nodes get a
// bounding-size bump to land at roughly the same perceived weight as a regular circular node.
private const val IMPORTANT_NODE_SCALE = 1.15f
private val UP_NEXT_LABEL_COLUMN_WIDTH = 22.dp
// Every node-like element (regular node, emphasized "next" node, ghost "+" node) sits centered inside
// a slot this wide, and the connector lines are centered on the same axis — so the bigger "next" node
// doesn't drift off the line the way it would if its own (larger) width shifted its center.
private val NODE_SLOT_SIZE = NODE_SIZE_NEXT
private val NODE_BORDER_WIDTH = 1.5.dp
private val NODE_BORDER_WIDTH_NEXT = 3.dp
private val LINE_HEIGHT = 20.dp
private val LINE_WIDTH = 3.dp
private val GLOW_ELEVATION = 10.dp
private val GLOW_ELEVATION_NEXT = 26.dp
private val PAST_ITEM_ALPHA = 0.65f
private val DRAG_HANDLE_COLUMN_WIDTH = 32.dp

/** Fixed tint for the "done" checkmark (both the node's inline check and the chip's trailing one) —
 *  always this green regardless of the item's own color, rather than a contrast-derived color. */
private val DONE_CHECK_COLOR = VoxSemanticColors.done

/** Fixed amber tint for the "important" star badge — same fixed-color-regardless-of-item-hue
 *  treatment as [DONE_CHECK_COLOR]. */
private val IMPORTANT_STAR_COLOR = VoxSemanticColors.important

/** 5-point star silhouette an important [TimelineNode] is shaped as (instead of a plain circle with a
 *  corner badge) — drawn as a closed [androidx.compose.ui.graphics.Path] alternating between the outer
 *  and inner radii, centered in and scaled to fill the composable's own bounds. */
private val StarShape = GenericShape { size, _ ->
    val points = 5
    val centerX = size.width / 2f
    val centerY = size.height / 2f
    val outerRadius = size.minDimension / 2f
    val innerRadius = outerRadius * 0.45f
    val angleStep = PI.toFloat() / points
    var angle = -PI.toFloat() / 2f
    moveTo(centerX + outerRadius * cos(angle), centerY + outerRadius * sin(angle))
    for (i in 1 until points * 2) {
        angle += angleStep
        val radius = if (i % 2 == 0) outerRadius else innerRadius
        lineTo(centerX + radius * cos(angle), centerY + radius * sin(angle))
    }
    close()
}

/** The single "next up" item across the whole list: the earliest not-yet-done item with an upcoming
 *  [ToDoItem.dueMillis], or — once every dated item's time has already passed (or none have a date at
 *  all) — simply the first not-done item in list order. Used to render one item slightly larger/bolder
 *  than the rest so it's obvious what to do next. */
private fun computeNextItemId(items: List<ToDoItem>, now: Long = System.currentTimeMillis()): Long? {
    val upcoming = items
        .filter { !it.done && it.dueMillis != null && it.dueMillis >= now }
        .minByOrNull { it.dueMillis!! }
    if (upcoming != null) return upcoming.id
    return items.firstOrNull { !it.done }?.id
}

/**
 * Where the "now" splitter line belongs within [items] (its display order) — the boundary between the
 * last TIMED item ([ToDoItem.dueMillis] != null) whose time has already passed and the next TIMED item
 * still in the future. Items with no due date are skipped entirely when computing this: they never
 * anchor the splitter themselves, and the splitter freely passes over any number of them sitting
 * between two timed items once the earlier timed one's time has passed. Returns the index to insert
 * the splitter BEFORE (so a value equal to `items.size` means "after every item"); null if no item in
 * the list has a due date at all.
 */
private fun computeNowSplitterIndex(items: List<ToDoItem>, now: Long = System.currentTimeMillis()): Int? {
    val timedIndices = items.indices.filter { items[it].dueMillis != null }
    if (timedIndices.isEmpty()) return null
    val lastPastTimedIndex = timedIndices.lastOrNull { items[it].dueMillis!! <= now }
    return if (lastPastTimedIndex != null) lastPastTimedIndex + 1 else timedIndices.first()
}

/** Whether this card has anything due today at all — what decides that a "now" line belongs on it. */
private fun hasAnyDueToday(items: List<ToDoItem>, today: LocalDate, zoneId: ZoneId): Boolean =
    items.any { item ->
        item.dueMillis?.let { Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate() == today } == true
    }

/** Whether any item from [fromIndex] onward (in display order) is still due today — used right
 *  after the "now" splitter to decide whether to show a "Nothing else today" line. Undated items
 *  are skipped (they never anchor a day either way); a dated item on a future day doesn't count. */
private fun hasMoreDueToday(items: List<ToDoItem>, fromIndex: Int, today: LocalDate, zoneId: ZoneId): Boolean {
    for (i in fromIndex until items.size) {
        val due = items[i].dueMillis ?: continue
        if (Instant.ofEpochMilli(due).atZone(zoneId).toLocalDate() == today) return true
    }
    return false
}

/** View-mode-only "ghosting" check: an item reads as behind-us — done, or dated and already overdue —
 *  and should fade back so the "next up" item stays the visual anchor. The "next" item itself is never
 *  ghosted even if it happens to be an overdue-but-undone fallback pick. */
private fun isPastItem(item: ToDoItem, isNext: Boolean, now: Long = System.currentTimeMillis()): Boolean =
    !isNext && (item.done || (item.dueMillis != null && item.dueMillis < now))

/** The color a task/node's own [colorArgb] should be darkened to for its glow — a tinted, non-neon
 *  shadow rather than the flat saturated fill color itself (which read as too harsh/neon at full
 *  shadow opacity). */
private fun glowColorFor(colorArgb: Long): Color {
    val c = Color(colorArgb.toInt())
    return Color(red = c.red * 0.7f, green = c.green * 0.7f, blue = c.blue * 0.7f, alpha = 0.9f)
}

/** White or near-black text, whichever contrasts more with [background] — same approach used
 *  wherever this codebase needs readable text on an arbitrary user-chosen color. */
private fun contrastingTextColor(background: Color): Color =
    if (background.luminance() > 0.5f) Color(0xFF1A1A1A) else Color.White

/** Blends [color] toward its own luminance-gray by [amount] (0 = unchanged, 1 = fully gray) — the
 *  "washed out" look for past/done items, on top of (not instead of) their row's own alpha reduction,
 *  so a faded item reads as genuinely behind-us rather than just a dimmer copy of the same hue. */
private fun desaturate(color: Color, amount: Float = 0.55f): Color {
    val gray = color.luminance()
    return Color(
        red = color.red + (gray - color.red) * amount,
        green = color.green + (gray - color.green) * amount,
        blue = color.blue + (gray - color.blue) * amount,
        alpha = color.alpha
    )
}

/**
 * A vertical sequence of colored circular nodes (one per [items]) connected by a line — solid in view
 * mode, dotted with faded ghost "+" insert-nodes at the very start/end in edit mode (matching the
 * spec: dotted connectors + faded "+" nodes "at the beginning and the end" suggest available insert
 * points, not a full insert-anywhere UI — mid-list insertion is done by inserting at an end then
 * dragging into place via [onReorderHandle]).
 *
 * In view mode, tapping a node toggles [ToDoItem.done]. In edit mode, tapping a task's chip opens its
 * editor; tapping a ghost node adds a new item at that end; each row exposes [onReorderHandle] modifier
 * hooks (from `Modifier.longPressDraggableHandle` in the caller) for drag-to-reorder.
 */
@Composable
fun ToDoNodeTimeline(
    items: List<ToDoItem>,
    isEditing: Boolean,
    onToggleDone: (ToDoItem) -> Unit,
    onTaskClick: (ToDoItem) -> Unit,
    onAddAtStart: () -> Unit,
    onAddAtEnd: () -> Unit,
    modifier: Modifier = Modifier,
    itemRowContent: @Composable (index: Int, item: ToDoItem, node: @Composable () -> Unit) -> Unit = { _, item, node ->
        DefaultTimelineRow(item, node, isEditing, onTaskClick, isNext = item.id == computeNextItemId(items))
    }
) {
    val zoneId = remember { ZoneId.systemDefault() }
    // Ticking, not sampled: every answer below is "as of now", and a list nobody touches used to keep
    // whatever now meant when it was last composed — the line, its clock, and which items read as
    // past all froze until something unrelated recomposed the card.
    val now by rememberNowMillis()
    val today = remember(now) { LocalDate.now(zoneId) }
    val nextItemId = remember(items, now) { computeNextItemId(items, now) }
    // View mode only: dated items are re-sorted chronologically among themselves; undated items stay
    // pinned at their original absolute index (drag-reorder still governs the real, stored order —
    // this is a display-only reshuffle).
    val displayItems = if (isEditing) items else remember(items) { sortedByDateKeepingUndatedInPlace(items) }
    // View-mode only — see computeNowSplitterIndex's doc comment for the exact placement rule.
    // A "now" line only means something on a card that has something today. A card whose items are
    // all next week was drawing one anyway, which read as if today were somehow part of that list.
    val hasItemToday = remember(displayItems, today) { hasAnyDueToday(displayItems, today, zoneId) }
    val nowSplitterIndex =
        if (isEditing || !hasItemToday) null
        else remember(displayItems, now) { computeNowSplitterIndex(displayItems, now) }
    val noMoreToday = nowSplitterIndex != null && !hasMoreDueToday(displayItems, nowSplitterIndex, today, zoneId)
    Column(modifier = modifier.fillMaxWidth()) {
        if (isEditing) {
            GhostAddRow(onClick = onAddAtStart, leadingOffset = UP_NEXT_LABEL_COLUMN_WIDTH)
            DottedConnector(leadingOffset = UP_NEXT_LABEL_COLUMN_WIDTH)
        }
        var groupDate: LocalDate? = null
        displayItems.forEachIndexed { index, item ->
            if (nowSplitterIndex == index) {
                NowSplitter(now)
                if (noMoreToday) NothingElseTodayLabel(now)
            }
            val itemDate = item.dueMillis?.let { Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate() }
            val startsNewGroup = !isEditing && itemDate != null && itemDate != groupDate
            if (startsNewGroup) {
                groupDate = itemDate
                DateGroupHeader(date = itemDate!!, today = today)
            } else if (index > 0) {
                if (isEditing) {
                    DottedConnector(leadingOffset = UP_NEXT_LABEL_COLUMN_WIDTH)
                } else {
                    SolidConnector(Color(item.colorArgb.toInt()), muted = isPastItem(item, item.id == nextItemId, now), leadingOffset = UP_NEXT_LABEL_COLUMN_WIDTH)
                }
            }
            val isNext = item.id == nextItemId
            itemRowContent(index, item) {
                TimelineNode(
                    colorArgb = item.colorArgb,
                    done = item.done,
                    emphasized = isNext,
                    isImportant = item.isImportant,
                    ghosted = !isEditing && isPastItem(item, isNext, now),
                    onClick = { if (!isEditing) onToggleDone(item) }
                )
            }
        }
        if (nowSplitterIndex == displayItems.size) {
            NowSplitter(now)
            if (noMoreToday) NothingElseTodayLabel(now)
        }
        if (isEditing) {
            DottedConnector(leadingOffset = UP_NEXT_LABEL_COLUMN_WIDTH)
            GhostAddRow(onClick = onAddAtEnd, leadingOffset = UP_NEXT_LABEL_COLUMN_WIDTH)
        }
    }
}

/** A horizontal "now" indicator line, inserted at [computeNowSplitterIndex]'s computed boundary — the
 *  small dot sits on the same node axis every connector/node is centered on, so it reads as part of
 *  the same timeline rather than an unrelated divider. */
@Composable
private fun NowSplitter(nowMillis: Long) {
    // The same line the day and week grids draw; this surface's gutter is the "up next" label
    // column plus the node slot, so the dot lands on the axis every node is centred on.
    NowLine(
        nowMillis = nowMillis,
        leadingWidth = UP_NEXT_LABEL_COLUMN_WIDTH + NODE_SLOT_SIZE - 8.dp,
        thickness = 1.5.dp,
        modifier = Modifier.padding(vertical = 2.dp)
    )
}

/** Shown right under [NowSplitter] when nothing else is due today. Centred on the card rather than
 *  indented to the timeline's text column: it is a remark about the day as a whole, not another row
 *  in the list, and reading it under the line that says "now" is the point. */
@Composable
private fun NothingElseTodayLabel(nowMillis: Long) {
    val languageManager = LocalLanguageManager.current
    val hour = remember(nowMillis) {
        Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).hour
    }
    val (leading, trailing) = remember(hour) { nothingElseTodayEmojis(hour) }
    Box(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "$leading ${languageManager.getString("nothing_else_today")} $trailing",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Reorders [items] so that every dated slot (an index whose original item has a non-null
 *  [ToDoItem.dueMillis]) is filled by the dated items in chronological order, while every undated
 *  slot keeps its original item untouched — i.e. only the dated items move, and only among
 *  themselves. */
private fun sortedByDateKeepingUndatedInPlace(items: List<ToDoItem>): List<ToDoItem> {
    val datedSorted = items.filter { it.dueMillis != null }.sortedBy { it.dueMillis }
    var datedCursor = 0
    return items.map { item -> if (item.dueMillis != null) datedSorted[datedCursor++] else item }
}

/** Day-grouping header for the view-mode timeline — "Today"/"Tomorrow"/`"EEE, d MMM"`, same
 *  three-way relative-day logic as `CalendarWidget`'s Glance-specific `dayLabel()`, reimplemented
 *  here in plain Compose since that one can't be called directly from a regular composable. */
@Composable
private fun DateGroupHeader(date: LocalDate, today: LocalDate) {
    val languageManager = LocalLanguageManager.current
    val label = when (date) {
        today -> languageManager.getString("today")
        today.plusDays(1) -> languageManager.getString("tomorrow")
        else -> date.format(DateTimeFormatter.ofPattern("EEE, d MMM"))
    }
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

/** Trailing time label shown when a task has [dueMillis] — red once overdue and not [done],
 *  tappable when [onClick] is given (edit mode's quick date/time re-edit; view mode passes none).
 *  [showDate] prefixes the date (edit mode, where the day-group headers are hidden); [emphasized]
 *  bolds it for the "next up" item. */
@Composable
private fun TimeLabel(
    dueMillis: Long?,
    done: Boolean,
    showDate: Boolean = false,
    emphasized: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    if (dueMillis == null) return
    val overdue = !done && dueMillis < System.currentTimeMillis()
    val color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    val date = Date(dueMillis)
    val text = if (showDate) {
        "${DateFormat.getDateInstance(DateFormat.SHORT).format(date)} ${DateFormat.getTimeInstance(DateFormat.SHORT).format(date)}"
    } else {
        DateFormat.getTimeInstance(DateFormat.SHORT).format(date)
    }
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Normal),
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    )
}

@Composable
private fun DefaultTimelineRow(
    item: ToDoItem,
    node: @Composable () -> Unit,
    isEditing: Boolean,
    onTaskClick: (ToDoItem) -> Unit,
    isNext: Boolean
) {
    // Ghosting used to be view-mode only, since editing meant re-arranging the list and every row
    // needed to stay at full strength — but past/done items looked identical to future ones in edit
    // mode too, which was confusing, so it now applies there as well.
    val ghosted = isPastItem(item, isNext)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().alpha(if (ghosted) PAST_ITEM_ALPHA else 1f)
    ) {
        Box(modifier = Modifier.width(UP_NEXT_LABEL_COLUMN_WIDTH), contentAlignment = Alignment.Center) {
            if (isNext) UpNextMarker(colorArgb = item.colorArgb)
        }
        node()
        Spacer(Modifier.width(10.dp))
        // Always clickable (not gated on isEditing, which is always false for this row's one caller,
        // the view-mode timeline) — tapping the chip opens the task's edit dialog directly, without
        // going through the card's own tap-anywhere-to-flip-into-edit-mode handler underneath it.
        TaskChip(item = item, clickable = true, onClick = { onTaskClick(item) }, emphasized = isNext, ghosted = ghosted)
        Spacer(Modifier.weight(1f))
        TimeLabel(dueMillis = item.dueMillis, done = item.done, emphasized = isNext)
    }
}

@Composable
fun TaskChip(
    item: ToDoItem,
    clickable: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    ghosted: Boolean = false
) {
    val languageManager = LocalLanguageManager.current
    val background = if (ghosted) desaturate(Color(item.colorArgb.toInt())) else Color(item.colorArgb.toInt())
    val textColor = contrastingTextColor(background)
    val isEmpty = item.text.isEmpty()
    val elevation = if (emphasized) GLOW_ELEVATION_NEXT else GLOW_ELEVATION
    val borderWidth = if (emphasized) NODE_BORDER_WIDTH_NEXT else NODE_BORDER_WIDTH
    Box(
        modifier = modifier
            .shadow(elevation, RoundedCornerShape(50), ambientColor = glowColorFor(item.colorArgb), spotColor = glowColorFor(item.colorArgb))
            .clip(RoundedCornerShape(50))
            .background(background)
            .border(borderWidth, glowColorFor(item.colorArgb), RoundedCornerShape(50))
            .then(if (clickable) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (item.isImportant) {
                Icon(Icons.Filled.Star, contentDescription = null, tint = IMPORTANT_STAR_COLOR, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(
                if (isEmpty) languageManager.getString("todo_new_item_hint") else item.text,
                color = if (isEmpty) textColor.copy(alpha = 0.6f) else textColor,
                style = when {
                    item.done -> MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                    emphasized -> MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = MaterialTheme.typography.bodyMedium.fontSize * 1.15f
                    )
                    else -> MaterialTheme.typography.bodyMedium
                }
            )
            if (item.done) {
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Filled.Check, contentDescription = null, tint = DONE_CHECK_COLOR, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/** [emphasized] ("next up") nodes render bigger than regular ones, but always centered inside the
 *  same [NODE_SLOT_SIZE] slot the connector lines are centered on — so growing the circle expands it
 *  evenly in both directions instead of shifting its center off the line. [ghosted] desaturates the
 *  node toward gray for past/done items; [isImportant] renders the node as a star silhouette (instead
 *  of a circle) rather than adding a separate corner badge. [emphasized] also draws a soft pulsing
 *  glow behind the node — self-contained animation, independent of the app's `animationsEnabled`
 *  setting. Public so [com.voxapps.calendarapp.ui.CalendarScreen] can reuse the exact same bullet
 *  (color/size/shape) for a to-do-flavored entry that's bleeding into the calendar grid — same visual
 *  identity as its own row in the to-do list, so it's unmistakably a task there too. */
@Composable
fun TimelineNode(
    colorArgb: Long,
    done: Boolean,
    onClick: () -> Unit,
    emphasized: Boolean = false,
    ghosted: Boolean = false,
    isImportant: Boolean = false
) {
    val color = if (ghosted) desaturate(Color(colorArgb.toInt())) else Color(colorArgb.toInt())
    val baseSize = if (emphasized) NODE_SIZE_NEXT else NODE_SIZE
    val size = if (isImportant) baseSize * IMPORTANT_NODE_SCALE else baseSize
    val elevation = if (emphasized) GLOW_ELEVATION_NEXT else GLOW_ELEVATION
    val borderWidth = if (emphasized) NODE_BORDER_WIDTH_NEXT else NODE_BORDER_WIDTH
    val shape = if (isImportant) StarShape else CircleShape
    Box(modifier = Modifier.size(NODE_SLOT_SIZE), contentAlignment = Alignment.Center) {
        if (emphasized) {
            PulsingGlow(color = glowColorFor(colorArgb), size = baseSize)
        }
        Box(
            modifier = Modifier
                .shadow(elevation, shape, ambientColor = glowColorFor(colorArgb), spotColor = glowColorFor(colorArgb))
                .size(size)
                .clip(shape)
                .background(color)
                .border(borderWidth, glowColorFor(colorArgb), shape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (done) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = DONE_CHECK_COLOR, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/** Soft translucent circle behind the "next up" node, continuously scaling up/fading out and looping
 *  back — an ambient pulse drawing the eye to the current item, independent of the node's own
 *  shadow/border. Sized relative to [size] (the node's own un-important-scaled bounding box) so it
 *  reads as emanating from the node rather than as an unrelated halo. */
@Composable
private fun PulsingGlow(color: Color, size: Dp) {
    val transition = rememberInfiniteTransition(label = "upNextGlow")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.9f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "upNextGlowScale"
    )
    val alpha by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "upNextGlowAlpha"
    )
    Box(
        modifier = Modifier
            .size(size * 1.6f)
            .scale(scale)
            .clip(CircleShape)
            .background(color.copy(alpha = color.alpha * alpha))
    )
}

/** Vertical "UP NEXT" text + a small triangle pointer, shown in the [UP_NEXT_LABEL_COLUMN_WIDTH]-wide
 *  marker column reserved on every timeline row — only the "next up" row actually populates it (see
 *  [DefaultTimelineRow]), so the node axis stays aligned whether or not a given row is "next". The
 *  text is rotated -90° with `wrapContentWidth(unbounded = true)` so its natural (unrotated) width
 *  isn't squeezed to the column's fixed width before the rotation is applied, which would otherwise
 *  wrap/clip it. */
@Composable
private fun UpNextMarker(colorArgb: Long) {
    val languageManager = LocalLanguageManager.current
    val color = Color(colorArgb.toInt())
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = languageManager.getString("todo_up_next"),
            color = color,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            maxLines = 1,
            modifier = Modifier
                .wrapContentWidth(unbounded = true)
                .rotate(-90f)
        )
        Spacer(Modifier.width(2.dp))
        Canvas(modifier = Modifier.size(width = 6.dp, height = 10.dp)) {
            val path = Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width, size.height / 2f)
                lineTo(0f, size.height)
                close()
            }
            drawPath(path, color = color)
        }
    }
}

/** Ghost "+" insert node — wrapped in a [NODE_SLOT_SIZE]-sized box (even though the visible circle is
 *  smaller, [GHOST_NODE_SIZE]) so its center lands on the exact same vertical axis the dotted/solid
 *  connectors and the (possibly bigger, emphasized) real nodes are centered on, instead of drifting a
 *  couple dp off since it'd otherwise be its own (smaller) width. [leadingOffset] shifts that axis
 *  right by the view-mode timeline's reserved "up next" marker column width, since [DefaultTimelineRow]
 *  puts that column before the node — callers without such a column (the edit-face timeline) leave it
 *  at the default 0.dp. */
@Composable
private fun GhostAddRow(onClick: () -> Unit, leadingOffset: Dp = 0.dp) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(leadingOffset))
        Box(modifier = Modifier.size(NODE_SLOT_SIZE), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(GHOST_NODE_SIZE)
                    .clip(CircleShape)
                    .border(1.5.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), CircleShape)
                    .clickable(onClick = onClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

/** [leadingOffset]: see [GhostAddRow] — same axis-shift for the view-mode timeline's reserved marker
 *  column. */
@Composable
private fun SolidConnector(color: Color, muted: Boolean = false, leadingOffset: Dp = 0.dp) {
    val effectiveColor = if (muted) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f) else color.copy(alpha = 0.6f)
    Box(
        modifier = Modifier
            .padding(start = leadingOffset + (NODE_SLOT_SIZE / 2) - (LINE_WIDTH / 2))
            .width(LINE_WIDTH)
            .height(LINE_HEIGHT)
            .background(effectiveColor)
    )
}

/**
 * Edit-mode variant of the timeline: a bounded-height, independently-scrolling [LazyColumn] (nested
 * inside the flipped card face) with drag-to-reorder — mirrors vox-commander's Rules Manager
 * (`RulesManagerScreen.kt`'s `ReorderableItem`/`rememberReorderableLazyListState`/
 * `longPressDraggableHandle` wiring) since [sh.calvin.reorderable] only operates on a real
 * [androidx.compose.foundation.lazy.LazyListState], not a plain [Column]. Reordering is optimistic
 * (mutates a local copy immediately for smooth drag feedback) and only persists via [onReorderCommitted]
 * once the drag gesture ends.
 */
@Composable
fun ToDoNodeTimelineEditable(
    items: List<ToDoItem>,
    onToggleDone: (ToDoItem) -> Unit,
    onTaskClick: (ToDoItem) -> Unit,
    onAddAt: (Int) -> Unit,
    onReorderCommitted: (List<ToDoItem>) -> Unit,
    onDeleteItem: (ToDoItem) -> Unit,
    onQuickEditDate: (ToDoItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    var localItems by remember(items) { mutableStateOf(items) }
    val nextItemId = remember(items) { computeNextItemId(items) }
    val lazyListState = rememberLazyListState()
    // While a drag is active, the trailing "+" row is replaced by a delete drop-zone (see below) —
    // dragging a task onto it is a valid reorder target too, so hovering it must be distinguished
    // from a normal reorder-into-last-position rather than mutating localItems for it.
    var draggingItemId by remember { mutableStateOf<Long?>(null) }
    var pendingDeleteKey by remember { mutableStateOf<Any?>(null) }
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        if (to.key == DELETE_ZONE_KEY) {
            pendingDeleteKey = from.key
        } else {
            pendingDeleteKey = null
            localItems = localItems.toMutableList().apply {
                val fromIndex = indexOfFirst { it.id == from.key }
                val toIndex = indexOfFirst { it.id == to.key }
                if (fromIndex >= 0 && toIndex >= 0) add(toIndex, removeAt(fromIndex))
            }
        }
        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }

    LazyColumn(state = lazyListState, modifier = modifier.heightIn(max = 360.dp)) {
        // A ghost "+" slot sits before the first item, between every pair, and after the last one —
        // one more insert point than there are items — so a task can be added at any position, not
        // just appended. Each slot's onClick carries the exact index to insert at.
        item(key = "ghost-0") {
            Column {
                GhostAddRow(onClick = { onAddAt(0) })
                DottedConnector()
            }
        }
        localItems.forEachIndexed { index, item ->
            item(key = item.id) {
                ReorderableItem(reorderableState, key = item.id) { isDragging ->
                    val elevation by animateDpAsState(if (isDragging) 8.dp else 0.dp, label = "todoDragElevation")
                    val isNext = item.id == nextItemId
                    val ghosted = isPastItem(item, isNext)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().alpha(if (ghosted) PAST_ITEM_ALPHA else 1f)
                    ) {
                        TimelineNode(
                            colorArgb = item.colorArgb,
                            done = item.done,
                            emphasized = isNext,
                            ghosted = ghosted,
                            isImportant = item.isImportant,
                            onClick = { onToggleDone(item) }
                        )
                        Spacer(Modifier.width(10.dp))
                        TaskChip(
                            item = item,
                            clickable = true,
                            onClick = { onTaskClick(item) },
                            modifier = Modifier.shadow(elevation, RoundedCornerShape(50)),
                            emphasized = isNext,
                            ghosted = ghosted
                        )
                        Spacer(Modifier.weight(1f))
                        TimeLabel(
                            dueMillis = item.dueMillis,
                            done = item.done,
                            showDate = true,
                            emphasized = isNext,
                            onClick = { onQuickEditDate(item) }
                        )
                        // Own fixed-width slot (rather than sitting flush after the variable-width
                        // time label) so every row's handle lands on the same x — a consistent rail
                        // immediately beside the list's color-picker column, not just "whatever's left
                        // after the time text".
                        Box(modifier = Modifier.width(DRAG_HANDLE_COLUMN_WIDTH), contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.DragHandle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .longPressDraggableHandle(
                                        onDragStarted = {
                                            haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                            draggingItemId = item.id
                                        },
                                        onDragStopped = {
                                            haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                            if (pendingDeleteKey == item.id) {
                                                onDeleteItem(item)
                                                pendingDeleteKey = null
                                            } else {
                                                onReorderCommitted(localItems)
                                                dateOrderMismatch(localItems, item)?.let(onQuickEditDate)
                                            }
                                            draggingItemId = null
                                        }
                                    )
                            )
                        }
                    }
                }
            }
            val isLastRealItem = index == localItems.lastIndex
            if (isLastRealItem && draggingItemId != null) {
                item(key = DELETE_ZONE_KEY) {
                    Column {
                        DottedConnector()
                        ReorderableItem(reorderableState, key = DELETE_ZONE_KEY) {
                            DeleteDropZone(highlighted = pendingDeleteKey != null)
                        }
                    }
                }
            } else {
                item(key = "ghost-${index + 1}") {
                    Column {
                        DottedConnector()
                        GhostAddRow(onClick = { onAddAt(index + 1) })
                        if (!isLastRealItem) DottedConnector()
                    }
                }
            }
        }
    }
}

/** Small bordered "drop here to delete" container that replaces the trailing add-row while any task
 *  in the timeline is being dragged — [highlighted] once the drag is actually hovering over it. */
@Composable
private fun DeleteDropZone(highlighted: Boolean) {
    val languageManager = LocalLanguageManager.current
    val errorColor = MaterialTheme.colorScheme.error
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .then(
                if (highlighted) {
                    Modifier.background(errorColor.copy(alpha = 0.18f))
                } else {
                    Modifier.border(1.5.dp, errorColor.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                }
            )
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Icon(Icons.Filled.DeleteOutline, contentDescription = null, tint = errorColor, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(languageManager.getString("todo_delete_drop_zone"), color = errorColor, style = MaterialTheme.typography.bodySmall)
    }
}

/** After a drag reorder, checks whether [moved] (now sitting somewhere in [items]) has a
 *  [ToDoItem.dueMillis] that's chronologically inconsistent with its new immediate neighbors —
 *  later than the next item's due time, or earlier than the previous item's — and returns [moved]
 *  if so, so the caller can prompt the user to fix the date instead of leaving a silent mismatch. */
private fun dateOrderMismatch(items: List<ToDoItem>, moved: ToDoItem): ToDoItem? {
    val dueMillis = moved.dueMillis ?: return null
    val index = items.indexOfFirst { it.id == moved.id }
    if (index < 0) return null
    val prevDue = items.getOrNull(index - 1)?.dueMillis
    val nextDue = items.getOrNull(index + 1)?.dueMillis
    val mismatched = (prevDue != null && dueMillis < prevDue) || (nextDue != null && dueMillis > nextDue)
    return if (mismatched) moved else null
}

/** [leadingOffset]: see [GhostAddRow] — same axis-shift for the view-mode timeline's reserved marker
 *  column. */
@Composable
private fun DottedConnector(leadingOffset: Dp = 0.dp) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    Canvas(
        modifier = Modifier
            .padding(start = leadingOffset + (NODE_SLOT_SIZE / 2) - (LINE_WIDTH / 2))
            .width(LINE_WIDTH)
            .height(LINE_HEIGHT)
    ) {
        drawLine(
            color = color,
            start = Offset(size.width / 2, 0f),
            end = Offset(size.width / 2, size.height),
            strokeWidth = size.width,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
        )
    }
}
