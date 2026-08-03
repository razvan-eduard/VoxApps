package com.voxapps.notes.ui.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.voxapps.notes.NotesActivity
import com.voxapps.notes.NotesApplication
import com.voxapps.notes.R
import com.voxapps.notes.data.NoteWithCategory
import com.voxapps.design.showRequirementToast
import com.voxapps.ipc.VoxAppsDiscovery
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxOcrRequest
import com.voxapps.notes.domain.llm.ScanRequestSender
import com.voxapps.notes.domain.localization.LanguageManager
import com.voxapps.notes.state.NotesUiState
import com.voxapps.notes.ui.CategoryColors
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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

        val uiState = container.notesStateManager.uiState
            .filterNot { it is NotesUiState.Loading }
            .first()

        val recentNotes = if (uiState is NotesUiState.Unlocked) {
            container.notesRepository.notesWithCategory.first()
        } else {
            emptyList()
        }

        val addIntent = Intent(context, NotesActivity::class.java).apply {
            putExtra(NotesActivity.EXTRA_QUICK_ADD, true)
        }
        val openAppIntent = Intent(context, NotesActivity::class.java)
        // Read the live flow, not getSnapshot() — that cached value is updated by its own
        // independent collector, racing against the collector that triggers this very redraw
        // (NotesContainer's combine()). A direct flow read has no such race (see CalendarWidget's
        // identical fix for the full reasoning).
        val locale = Locale.forLanguageTag(container.settingsRepository.settingsFlow.first().language)
        val scanEnabled = VoxAppsDiscovery.isAppInstalled(context, VoxIpc.VISION_PACKAGE) &&
            VoxAppsDiscovery.isCommanderInstalled(context)

        provideContent {
            GlanceTheme {
                NotesWidgetContent(
                    locked = uiState is NotesUiState.Locked,
                    notes = recentNotes,
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
    languageManager: LanguageManager,
    addIntent: Intent,
    openAppIntent: Intent,
    locale: Locale,
    scanEnabled: Boolean
) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.surface)
            .padding(12.dp)
    ) {
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .clickable(actionStartActivity(openAppIntent)),
            verticalAlignment = Alignment.Vertical.CenterVertically
        ) {
            Text(
                text = languageManager.getString("widget_app_name"),
                style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp, color = GlanceTheme.colors.onSurface)
            )
            Spacer(modifier = GlanceModifier.defaultWeight())
            val scanIconTint = ColorFilter.tint(if (scanEnabled) GlanceTheme.colors.primary else GlanceTheme.colors.onSurfaceVariant)
            Image(
                provider = ImageProvider(R.drawable.ic_scan),
                contentDescription = languageManager.getString("capture_mode_single"),
                colorFilter = scanIconTint,
                modifier = GlanceModifier.size(25.dp).clickable(actionRunCallback<NotesWidgetScanSingleAction>())
            )
            Spacer(modifier = GlanceModifier.width(6.dp))
            Image(
                provider = ImageProvider(R.drawable.ic_stitch),
                contentDescription = languageManager.getString("capture_mode_stitch"),
                colorFilter = scanIconTint,
                modifier = GlanceModifier.size(25.dp).clickable(actionRunCallback<NotesWidgetScanStitchAction>())
            )
            Spacer(modifier = GlanceModifier.width(6.dp))
            Image(
                provider = ImageProvider(R.drawable.ic_batch),
                contentDescription = languageManager.getString("capture_mode_batch"),
                colorFilter = scanIconTint,
                modifier = GlanceModifier.size(25.dp).clickable(actionRunCallback<NotesWidgetScanBatchAction>())
            )
        }

        Spacer(modifier = GlanceModifier.height(8.dp))

        Box(modifier = GlanceModifier.defaultWeight()) {
            if (locked) {
                Text(
                    text = languageManager.getString("locked_title"),
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant)
                )
            } else {
                RecentNotesList(notes, languageManager, locale)
            }
        }

        WidgetAddButton(text = languageManager.getString("widget_add_button"), addIntent = addIntent)
    }
}

/** Full-width, bordered "+ X" button pinned to the widget's bottom edge — the manual add entry
 * point. Glance has no dedicated border modifier, so the border is a slightly larger, differently
 * colored outer Box behind a slightly inset, differently colored inner Box. */
@Composable
private fun WidgetAddButton(text: String, addIntent: Intent) {
    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .cornerRadius(10.dp)
            .background(GlanceTheme.colors.primary)
            .clickable(actionStartActivity(addIntent))
    ) {
        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .padding(1.5.dp)
                .cornerRadius(9.dp)
                .background(GlanceTheme.colors.primaryContainer)
        ) {
            Text(
                text = text,
                style = TextStyle(
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = GlanceTheme.colors.onPrimaryContainer,
                    textAlign = TextAlign.Center
                ),
                modifier = GlanceModifier.fillMaxWidth().padding(vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun RecentNotesList(notes: List<NoteWithCategory>, languageManager: LanguageManager, locale: Locale) {
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

    LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
        items(grouped.entries.toList(), itemId = { it.key.toEpochDay() }) { (date, items) ->
            val isToday = date == today
            Column {
                DaySeparatorLabel(date, today, languageManager, locale)
                Box(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .height(if (isToday) 2.dp else 1.dp)
                        .background(if (isToday) GlanceTheme.colors.primary else GlanceTheme.colors.outline)
                ) {}

                items.forEach { item ->
                    val editIntent = Intent(context, NotesActivity::class.java).apply {
                        putExtra(NotesActivity.EXTRA_EDIT_NOTE_ID, item.note.id)
                    }
                    val hasTitle = !item.note.title.isNullOrBlank()
                    val categoryColor = item.category?.let { CategoryColors.fromStored(it.colorArgb) }
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
                            Text(
                                text = item.note.title.orEmpty(),
                                maxLines = 1,
                                style = TextStyle(fontSize = 15.sp, color = GlanceTheme.colors.onSurface),
                                modifier = GlanceModifier.defaultWeight()
                            )
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

private const val ROW_TINT_ALPHA = 0.18f

private fun dayLabel(date: LocalDate, today: LocalDate, languageManager: LanguageManager, locale: Locale): String =
    if (date == today) {
        languageManager.getString("today")
    } else {
        date.format(DateTimeFormatter.ofPattern("EEE, d MMM", locale))
    }

/** Centered day-group separator — plain text for every day, "Today" only distinguished by a
 * bolder/larger label and a thicker divider line underneath it (see the caller), not a background
 * badge (reads too much like a button). */
@Composable
private fun DaySeparatorLabel(date: LocalDate, today: LocalDate, languageManager: LanguageManager, locale: Locale) {
    val isToday = date == today
    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = dayLabel(date, today, languageManager, locale),
            style = TextStyle(
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                fontSize = if (isToday) 13.sp else 12.sp,
                color = GlanceTheme.colors.primary
            )
        )
    }
}
