package com.voxapps.notes.ui.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.voxapps.notes.NotesActivity
import com.voxapps.notes.NotesApplication
import com.voxapps.notes.R
import com.voxapps.notes.data.NoteWithCategory
import com.voxapps.notes.data.NotesAttachments
import com.voxapps.design.showRequirementToast
import com.voxapps.ipc.VoxAppsDiscovery
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxOcrRequest
import com.voxapps.notes.domain.llm.ScanRequestSender
import com.voxapps.notes.domain.localization.LanguageManager
import com.voxapps.notes.state.NotesUiState
import com.voxapps.notes.ui.CategoryColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.voxapps.widget.VoxWidgetScaffold
import com.voxapps.widget.WidgetScanRow
import com.voxapps.widget.WidgetDayFormats
import com.voxapps.widget.DaySeparatorStyle
import com.voxapps.widget.DaySeparatorLabel

/**
 * Home-screen widget: a snapshot of recent notes plus quick "Add"/"Scan" actions — lives entirely
 * inside vox-notes (mirrors vox-calendar's CalendarWidget / vox-expenses' ExpensesWidget). The list
 * is read directly from [com.voxapps.notes.data.NotesRepository] rather than
 * [NotesUiState.Unlocked.notes] on purpose: that field is already filtered by whatever
 * category/date filter happens to be active in the foreground UI, which would make the widget's
 * "recent notes" snapshot silently depend on unrelated in-app UI state — the widget only borrows
 * [NotesUiState] to decide Locked vs Unlocked (the same biometric-lock check
 * [com.voxapps.notes.ui.NotesRoot] uses), not its filtered note list.
 */
class NotesWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = (context.applicationContext as NotesApplication).container

        val addIntent = Intent(context, NotesActivity::class.java).apply {
            putExtra(NotesActivity.EXTRA_QUICK_ADD, true)
        }
        val openAppIntent = Intent(context, NotesActivity::class.java)
        val scanEnabled = VoxAppsDiscovery.isAppInstalled(context, VoxIpc.VISION_PACKAGE) &&
            VoxAppsDiscovery.isCommanderInstalled(context)

        // Every dynamic value is collected INSIDE the composition, never read into a val out here:
        // provideGlance runs once per Glance session, while an updateAll() on a live session only
        // RECOMPOSES the content lambda — data captured out here would be redrawn verbatim forever,
        // which is exactly how the widget used to freeze one change behind until the session was
        // rebuilt by a process death or launcher restart. As composition state, each flow emission
        // recomposes with fresh data and Glance republishes the RemoteViews (mirrors
        // ExpensesWidget/CalendarWidget's identical fix).
        provideContent {
            val uiState by container.notesStateManager.uiState.collectAsState()
            val allNotes by container.notesRepository.notesWithCategory.collectAsState(initial = emptyList())
            val attachedNoteIds by remember {
                container.attachmentDao.observeRecordIdsWithAttachments(NotesAttachments.RECORD_TYPE)
            }.collectAsState(initial = emptyList())
            val settingsSnapshot by container.settingsRepository.settingsFlow
                .collectAsState(initial = container.settingsRepository.getSnapshot())
            val locale = Locale.forLanguageTag(settingsSnapshot.language)
            val recentNotes = if (uiState is NotesUiState.Unlocked) allNotes else emptyList()

            GlanceTheme {
                NotesWidgetContent(
                    locked = uiState is NotesUiState.Locked,
                    notes = recentNotes,
                    attachedNoteIds = attachedNoteIds.toSet(),
                    languageManager = container.languageManager,
                    addIntent = addIntent,
                    openAppIntent = openAppIntent,
                    locale = locale,
                    scanEnabled = scanEnabled
                )
            }
        }
    }
}

// Glance/RemoteViews has no expand-in-place speed dial like a real Compose FAB can render — the
// widget instead shows 3 small static icons (single/stitch/batch), each its own ActionCallback
// sharing this one gated launch helper.
private suspend fun runWidgetScan(context: Context, captureMode: String) {
    val container = (context.applicationContext as NotesApplication).container
    val languageManager = container.languageManager
    when {
        !VoxAppsDiscovery.isAppInstalled(context, VoxIpc.VISION_PACKAGE) ->
            showRequirementToast(context, languageManager.getString("vision_required_message"))
        !VoxAppsDiscovery.isCommanderInstalled(context) ->
            showRequirementToast(context, languageManager.getString("commander_required_message"))
        else -> ScanRequestSender.send(context, captureMode)
    }
}

class NotesWidgetScanSingleAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        runWidgetScan(context, VoxOcrRequest.CAPTURE_MODE_SINGLE)
}

class NotesWidgetScanStitchAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        runWidgetScan(context, VoxOcrRequest.CAPTURE_MODE_STITCH)
}

class NotesWidgetScanBatchAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        runWidgetScan(context, VoxOcrRequest.CAPTURE_MODE_BATCH)
}

@Composable
private fun NotesWidgetContent(
    locked: Boolean,
    notes: List<NoteWithCategory>,
    attachedNoteIds: Set<Long>,
    languageManager: LanguageManager,
    addIntent: Intent,
    openAppIntent: Intent,
    locale: Locale,
    scanEnabled: Boolean
) {
    VoxWidgetScaffold(
        title = languageManager.getString("widget_app_name"),
        openAppAction = actionStartActivity(openAppIntent),
        addButtonText = languageManager.getString("widget_add_button"),
        addAction = actionStartActivity(addIntent),
        locked = locked,
        lockedText = languageManager.getString("locked_title"),
        scan = WidgetScanRow(
            enabled = scanEnabled,
            singleAction = actionRunCallback<NotesWidgetScanSingleAction>(),
            stitchAction = actionRunCallback<NotesWidgetScanStitchAction>(),
            batchAction = actionRunCallback<NotesWidgetScanBatchAction>(),
            singleDescription = languageManager.getString("capture_mode_single"),
            stitchDescription = languageManager.getString("capture_mode_stitch"),
            batchDescription = languageManager.getString("capture_mode_batch")
        )
    ) {
        RecentNotesList(notes, attachedNoteIds, languageManager, locale)
    }
}

@Composable
private fun RecentNotesList(notes: List<NoteWithCategory>, attachedNoteIds: Set<Long>, languageManager: LanguageManager, locale: Locale) {
    val zoneId = ZoneId.systemDefault()
    val today = LocalDate.now(zoneId)
    val recent = notes
        .sortedByDescending { it.note.createdAt }
        .take(20)

    if (recent.isEmpty()) {
        Text(
            text = languageManager.getString("widget_no_recent_notes"),
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant)
        )
        return
    }

    // groupBy preserves first-seen key order, and recent is already sorted newest-first, so the
    // resulting day groups come out in reverse-chronological order for free.
    val grouped = recent.groupBy { Instant.ofEpochMilli(it.note.createdAt).atZone(zoneId).toLocalDate() }
    val context = LocalContext.current

    // Flattened so every day-header and every note is its own LazyColumn item — confirmed on-device
    // (screenshot + a temporary item-count log): nesting a whole day's notes inside a single lazy item
    // (one Column with a variable-length .forEach of note Rows) silently truncates that item's content
    // once a day has "enough" notes (a 4-note day rendered fully, a 13-note day rendered only 2) — this
    // is Glance/RemoteViews capping how much a single non-lazy composable subtree can contain, not a
    // scroll or data problem. Each row needs to be its own lazy item for Glance to actually virtualize
    // it instead of flattening a whole day into one oversized static subtree.
    val rows = buildList {
        for ((date, items) in grouped) {
            add(WidgetRow.DayHeader(date))
            items.forEach { add(WidgetRow.NoteRow(it)) }
        }
    }

    LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
        items(rows, itemId = { it.itemId }) { row ->
            when (row) {
                is WidgetRow.DayHeader -> {
                    val isToday = row.date == today
                    Column {
                        DaySeparatorLabel(row.date, today, languageManager, locale)
                        Box(
                            modifier = GlanceModifier
                                .fillMaxWidth()
                                .height(if (isToday) 2.dp else 1.dp)
                                .background(if (isToday) GlanceTheme.colors.primary else GlanceTheme.colors.outline)
                        ) {}
                    }
                }
                is WidgetRow.NoteRow -> {
                    val item = row.item
                    val editIntent = Intent(context, NotesActivity::class.java).apply {
                        putExtra(NotesActivity.EXTRA_EDIT_NOTE_ID, item.note.id)
                    }
                    val hasTitle = !item.note.title.isNullOrBlank()
                    val categoryColor = item.category?.let { CategoryColors.fromStored(it.colorArgb) }
                    Column {
                        Row(
                            modifier = GlanceModifier
                                .fillMaxWidth()
                                .cornerRadius(6.dp)
                                .let { m -> if (categoryColor != null) m.background(categoryColor.copy(alpha = ROW_TINT_ALPHA)) else m }
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                                .clickable(actionStartActivity(editIntent)),
                            verticalAlignment = Alignment.Vertical.CenterVertically
                        ) {
                            if (hasTitle) {
                                Row(modifier = GlanceModifier.defaultWeight(), verticalAlignment = Alignment.Vertical.CenterVertically) {
                                    Text(
                                        text = item.note.title.orEmpty(),
                                        maxLines = 1,
                                        style = TextStyle(fontSize = 15.sp, color = GlanceTheme.colors.onSurface),
                                        modifier = GlanceModifier.defaultWeight()
                                    )
                                    if (item.note.id in attachedNoteIds) {
                                        Spacer(modifier = GlanceModifier.width(4.dp))
                                        Image(
                                            provider = ImageProvider(R.drawable.ic_attachment),
                                            contentDescription = null,
                                            colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
                                            modifier = GlanceModifier.size(12.dp)
                                        )
                                    }
                                }
                                if (item.note.text.isNotBlank()) {
                                    Spacer(modifier = GlanceModifier.width(8.dp))
                                    Text(
                                        text = item.note.text,
                                        maxLines = 2,
                                        style = TextStyle(
                                            fontSize = 12.sp,
                                            color = GlanceTheme.colors.outline,
                                            textAlign = TextAlign.End
                                        ),
                                        modifier = GlanceModifier.defaultWeight()
                                    )
                                }
                            } else {
                                Text(
                                    text = item.note.text,
                                    maxLines = 2,
                                    style = TextStyle(fontSize = 15.sp, color = GlanceTheme.colors.onSurface),
                                    modifier = GlanceModifier.defaultWeight()
                                )
                            }
                        }
                        Spacer(modifier = GlanceModifier.height(2.dp))
                    }
                }
            }
        }
    }
}

/** One flattened row in the widget's note list — see [RecentNotesList]'s doc comment for why this
 *  flattening exists. [itemId] must be stable and unique across both variants: day epoch-days are
 *  always >= 0, so headers are encoded negative to guarantee no collision with note row ids (which are
 *  Room auto-increment ids, always positive). */
private sealed class WidgetRow(val itemId: Long) {
    class DayHeader(val date: LocalDate) : WidgetRow(-(date.toEpochDay()) - 1)
    class NoteRow(val item: NoteWithCategory) : WidgetRow(item.note.id)
}

private const val ROW_TINT_ALPHA = 0.18f

private fun dayLabel(date: LocalDate, today: LocalDate, languageManager: LanguageManager, locale: Locale): String =
    if (date == today) {
        languageManager.getString("today")
    } else {
        WidgetDayFormats.weekday(date, locale)
    }

/** The day heading for this widget: its own wording, the shared presentation. */
@Composable
private fun DaySeparatorLabel(date: LocalDate, today: LocalDate, languageManager: LanguageManager, locale: Locale) {
    DaySeparatorLabel(
        text = dayLabel(date, today, languageManager, locale),
        isToday = date == today,
        style = DaySeparatorStyle.Plain
    )
}
