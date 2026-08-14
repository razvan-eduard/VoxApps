package com.voxapps.calendarapp.ui.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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
import androidx.glance.unit.ColorProvider
import com.voxapps.calendarapp.CalendarActivity
import com.voxapps.calendarapp.CalendarApplication
import com.voxapps.calendarapp.R
import com.voxapps.calendarapp.data.CalendarAttachments
import com.voxapps.calendarapp.data.CalendarEntryWithTags
import com.voxapps.calendarapp.data.CalendarLayer
import com.voxapps.calendarapp.domain.llm.CalendarScanRequestSender
import com.voxapps.design.effects.TodayEffect
import com.voxapps.design.effects.TodayEffectStyle
import com.voxapps.design.showRequirementToast
import com.voxapps.design.toEnumOr
import com.voxapps.ipc.VoxAppsDiscovery
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxOcrRequest
import com.voxapps.calendarapp.domain.localization.LanguageManager
import com.voxapps.calendarapp.state.CalendarUiState
import com.voxapps.calendarapp.ui.LayerColors
import com.voxapps.calendarapp.ui.nothingElseTodayEmojis
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import com.voxapps.widget.VoxWidgetScaffold
import com.voxapps.widget.WidgetDayCards
import com.voxapps.widget.WidgetDayChrome
import com.voxapps.widget.WidgetScanRow
import com.voxapps.widget.WidgetDayFormats
import com.voxapps.widget.DaySeparatorStyle
import com.voxapps.widget.DaySeparatorLabel

/**
 * Home-screen widget: a snapshot of upcoming entries plus quick "Add"/"Scan" actions — lives
 * entirely inside vox-calendar (not centralized in Commander, see the design discussion this
 * mirrors: each satellite hosts its own widget since it already has direct in-process access to
 * its own repository/state, no IPC needed). Reuses [com.voxapps.calendarapp.state.CalendarUiState]
 * exactly as [com.voxapps.calendarapp.ui.CalendarRoot] does, so a biometric-locked session shows
 * the same locked placeholder here as in-app — this is a plain read of the existing reactive
 * state, not new lock-bypass logic.
 */
class CalendarWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = (context.applicationContext as CalendarApplication).container

        val addIntent = Intent(context, CalendarActivity::class.java).apply {
            putExtra(CalendarActivity.EXTRA_QUICK_ADD, true)
        }
        val openAppIntent = Intent(context, CalendarActivity::class.java)
        val scanEnabled = VoxAppsDiscovery.isAppInstalled(context, VoxIpc.VISION_PACKAGE) &&
            VoxAppsDiscovery.isCommanderInstalled(context)

        // Every dynamic value is collected INSIDE the composition, never read into a val out here:
        // provideGlance runs once per Glance session, while an updateAll() on a live session only
        // RECOMPOSES the content lambda — data captured out here would be redrawn verbatim forever,
        // which is exactly how the widget used to freeze one change behind until the session was
        // rebuilt by a process death or launcher restart. As composition state, each flow emission
        // recomposes with fresh data and Glance republishes the RemoteViews.
        //
        // Entries come from CalendarRepository rather than CalendarUiState.Unlocked.entries on
        // purpose: that field is already filtered by whatever layer-visibility/tag filter happens
        // to be active in the foreground UI (see CalendarFilter.apply in CalendarStateManager),
        // which made the widget's "upcoming entries" snapshot silently depend on unrelated in-app
        // filter state. Mirrors ExpensesWidget.
        provideContent {
            val uiState by container.calendarStateManager.uiState.collectAsState()
            val entries by container.calendarRepository.entriesWithTags.collectAsState(initial = emptyList())
            val attachedEntryIds by remember {
                container.attachmentDao.observeRecordIdsWithAttachments(CalendarAttachments.RECORD_TYPE)
            }.collectAsState(initial = emptyList())
            // The flow, not getSnapshot() — the cached snapshot is updated by its own independent
            // collector with no ordering guarantee against whatever triggered this recomposition;
            // the collected flow carries the value that caused it.
            val settingsSnapshot by container.settingsRepository.settingsFlow
                .collectAsState(initial = container.settingsRepository.getSnapshot())
            val locale = Locale.forLanguageTag(settingsSnapshot.language)

            GlanceTheme {
                CalendarWidgetContent(
                    uiState = uiState,
                    entries = entries,
                    attachedEntryIds = attachedEntryIds.toSet(),
                    languageManager = container.languageManager,
                    addIntent = addIntent,
                    openAppIntent = openAppIntent,
                    locale = locale,
                    scanEnabled = scanEnabled,
                    showEventDetails = settingsSnapshot.showEventDetailsInWidget,
                    borderEnabled = settingsSnapshot.widgetBorderEnabled,
                    borderThicknessDp = settingsSnapshot.widgetBorderThicknessDp,
                    borderColor = Color(settingsSnapshot.widgetBorderColorArgb.toInt()),
                    // todayEffectShowInWidget is a widget-only opt-out, independent of the in-app
                    // effect — collapsing it to NONE here (rather than threading a separate boolean
                    // through CalendarWidgetContent/UpcomingEntriesList) reuses the existing
                    // effect==NONE gate below with no signature changes.
                    todayEffect = if (settingsSnapshot.todayEffectShowInWidget) {
                        settingsSnapshot.todayEffect.toEnumOr(TodayEffect.NONE)
                    } else {
                        TodayEffect.NONE
                    },
                    todayEffectStyle = settingsSnapshot.todayEffectStyle.toEnumOr(TodayEffectStyle.RING),
                    todayEffectColor = Color(settingsSnapshot.todayEffectColor.toInt())
                )
            }
        }
    }
}

// Glance/RemoteViews has no expand-in-place speed dial like a real Compose FAB can render — the
// widget instead shows 3 small static icons (single/stitch/batch), each its own ActionCallback
// sharing this one gated launch helper.
private suspend fun runWidgetScan(context: Context, captureMode: String) {
    val container = (context.applicationContext as CalendarApplication).container
    val languageManager = container.languageManager
    when {
        !VoxAppsDiscovery.isAppInstalled(context, VoxIpc.VISION_PACKAGE) ->
            showRequirementToast(context, languageManager.getString("vision_required_message"))
        !VoxAppsDiscovery.isCommanderInstalled(context) ->
            showRequirementToast(context, languageManager.getString("commander_required_message"))
        else -> CalendarScanRequestSender.send(context, captureMode)
    }
}

class CalendarWidgetScanSingleAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        runWidgetScan(context, VoxOcrRequest.CAPTURE_MODE_SINGLE)
}

class CalendarWidgetScanStitchAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        runWidgetScan(context, VoxOcrRequest.CAPTURE_MODE_STITCH)
}

class CalendarWidgetScanBatchAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        runWidgetScan(context, VoxOcrRequest.CAPTURE_MODE_BATCH)
}

@Composable
private fun CalendarWidgetContent(
    uiState: CalendarUiState,
    entries: List<CalendarEntryWithTags>,
    attachedEntryIds: Set<Long>,
    languageManager: LanguageManager,
    addIntent: Intent,
    openAppIntent: Intent,
    locale: Locale,
    scanEnabled: Boolean,
    showEventDetails: Boolean,
    borderEnabled: Boolean,
    borderThicknessDp: Int,
    borderColor: Color,
    todayEffect: TodayEffect,
    todayEffectStyle: TodayEffectStyle,
    todayEffectColor: Color
) {
    VoxWidgetScaffold(
        title = languageManager.getString("widget_app_name"),
        openAppAction = actionStartActivity(openAppIntent),
        addButtonText = languageManager.getString("widget_add_button"),
        addAction = actionStartActivity(addIntent),
        locked = uiState is CalendarUiState.Locked,
        lockedText = languageManager.getString("locked_title"),
        scan = WidgetScanRow(
            enabled = scanEnabled,
            singleAction = actionRunCallback<CalendarWidgetScanSingleAction>(),
            stitchAction = actionRunCallback<CalendarWidgetScanStitchAction>(),
            batchAction = actionRunCallback<CalendarWidgetScanBatchAction>(),
            singleDescription = languageManager.getString("capture_mode_single"),
            stitchDescription = languageManager.getString("capture_mode_stitch"),
            batchDescription = languageManager.getString("capture_mode_batch")
        )
    ) {
        if (uiState is CalendarUiState.Unlocked) {
            UpcomingEntriesList(
                entries, uiState.layers, languageManager, locale, showEventDetails,
                borderEnabled, borderThicknessDp, borderColor,
                todayEffect, todayEffectStyle, todayEffectColor, attachedEntryIds
            )
        }
    }
}

@Composable
private fun UpcomingEntriesList(
    entries: List<CalendarEntryWithTags>,
    layers: List<CalendarLayer>,
    languageManager: LanguageManager,
    locale: Locale,
    showEventDetails: Boolean,
    borderEnabled: Boolean,
    borderThicknessDp: Int,
    borderColor: Color,
    todayEffect: TodayEffect,
    todayEffectStyle: TodayEffectStyle,
    todayEffectColor: Color,
    attachedEntryIds: Set<Long>
) {
    val zoneId = ZoneId.systemDefault()
    val today = LocalDate.now(zoneId)
    // Cutoff is the START of today, not this exact instant — an entry timed earlier today (or an
    // all-day entry, whose startMillis is midnight) must stay visible for the rest of today, not
    // disappear the moment its clock time passes.
    val startOfToday = today.atStartOfDay(zoneId).toInstant().toEpochMilli()
    // Non-null throughout this function: entries comes from entriesWithTags, which already excludes
    // dateless to-do items at the query level (see CalendarEntryDao.observeEntriesWithTags).
    val upcoming = entries
        .filter { !it.entry.completed && (it.entry.endMillis ?: it.entry.startMillis!!) >= startOfToday }
        .sortedBy { it.entry.startMillis!! }
        .take(20)

    val context = LocalContext.current
    val layerById = layers.associateBy { it.id }
    // groupBy preserves first-seen key order, and upcoming is already sorted by startMillis, so
    // the resulting day groups come out in chronological order for free. Clamp each entry's group
    // date to "today" at the earliest — a multi-day entry that started before today (still shown
    // because it hasn't ended yet, per the filter above) must not produce a day-card dated in the
    // past; it belongs in today's card instead.
    val grouped = upcoming.groupBy { item ->
        val startDate = Instant.ofEpochMilli(item.entry.startMillis!!).atZone(zoneId).toLocalDate()
        if (startDate.isBefore(today)) today else startDate
    }.toList().toMutableList()

    // Ensure Today is always present as the first entry
    if (grouped.none { it.first == today }) grouped.add(0, today to emptyList())

    // Widgets (Glance/RemoteViews) can't run the animated pulse the in-app effect uses; this maps
    // the same effect+style+color settings onto the static chrome WidgetDayCards renders.
    val showTodayHighlight = todayEffect != TodayEffect.NONE && todayEffectStyle != TodayEffectStyle.NONE
    val chrome = WidgetDayChrome(
        borderEnabled = borderEnabled,
        borderThicknessDp = borderThicknessDp,
        borderColor = borderColor,
        todayRing = showTodayHighlight && todayEffectStyle != TodayEffectStyle.BACKGROUND,
        todayBackground = showTodayHighlight && todayEffectStyle != TodayEffectStyle.RING,
        todayColor = todayEffectColor
    )

    WidgetDayCards(
        groups = grouped,
        today = today,
        chrome = chrome,
        emptyDayText = languageManager.getString("widget_nothing_today"),
        dayLabel = { date -> dayLabel(date, today, languageManager, locale) }
    ) { date, items ->
        val isToday = date == today
            // Same "now" splitter as the to-do list's timeline (ToDoNodeTimeline.kt's
            // NowSplitter) — today's own section only, and only once it actually has events.
            // Every widget item already has a real startMillis (entriesWithTags excludes
            // dateless to-do rows), so this is simpler than the to-do version: no items to
            // skip, just the first index whose time is still in the future.
            val now = System.currentTimeMillis()
            val nowSplitIndex = if (isToday) {
                items.indexOfFirst { it.entry.startMillis!! > now }.let { if (it == -1) items.size else it }
            } else {
                null
            }
            items.forEachIndexed { index, item ->
                if (nowSplitIndex == index) NowSplitterRow()
                val editIntent = Intent(context, CalendarActivity::class.java).apply {
                    putExtra(CalendarActivity.EXTRA_EDIT_ENTRY_ID, item.entry.id)
                }
                // To-do-flavored entries (bleeding into the widget the same way they bleed
                // into the in-app grid) get the to-do list's own bullet+pill treatment — same
                // color/shape as CalendarScreen.kt's EntryRow — instead of the plain
                // layer-tinted row background every other entry uses here, so they read as
                // unmistakably a task in the widget too (Glance can't draw the star shape for
                // isImportant the way TimelineNode does in-app — plain circle only here).
                val isTodoFlavored = item.entry.listId != null
                val layerColor = layerById[item.entry.layerId]?.let { LayerColors.fromStored(it.colorArgb) }
                Column(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .cornerRadius(6.dp)
                        .let { m -> if (!isTodoFlavored && layerColor != null) m.background(layerColor.copy(alpha = ROW_TINT_ALPHA)) else m }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                        .clickable(actionStartActivity(editIntent))
                ) {
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Vertical.CenterVertically
                    ) {
                        if (isTodoFlavored) {
                            val todoColor = Color((item.entry.colorArgb ?: 0xFF9E9E9EL).toInt())
                            val todoTextColor = if (todoColor.luminance() > 0.5f) Color(0xFF1A1A1A) else Color.White
                            Box(modifier = GlanceModifier.size(10.dp).cornerRadius(5.dp).background(todoColor)) {}
                            Spacer(modifier = GlanceModifier.width(6.dp))
                            // Pill styling applied directly on Text's own modifier — no
                            // wrapping Box — since combining .defaultWeight() with a
                            // background+cornerRadius Box that CONTAINS a Text rendered the
                            // pill's color but left the text invisible in Glance's RemoteViews
                            // translation.
                            Text(
                                text = item.entry.title,
                                maxLines = 1,
                                style = TextStyle(fontSize = 15.sp, color = ColorProvider(todoTextColor)),
                                modifier = GlanceModifier
                                    .defaultWeight()
                                    .cornerRadius(50.dp)
                                    .background(todoColor)
                                    .padding(horizontal = 10.dp, vertical = 3.dp)
                            )
                        } else {
                            Row(modifier = GlanceModifier.defaultWeight(), verticalAlignment = Alignment.Vertical.CenterVertically) {
                                Text(
                                    text = item.entry.title,
                                    maxLines = 1,
                                    style = TextStyle(fontSize = 15.sp, color = GlanceTheme.colors.onSurface),
                                    modifier = GlanceModifier.defaultWeight()
                                )
                                if (item.entry.id in attachedEntryIds) {
                                    Spacer(modifier = GlanceModifier.width(4.dp))
                                    Image(
                                        provider = ImageProvider(R.drawable.ic_attachment),
                                        contentDescription = null,
                                        colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
                                        modifier = GlanceModifier.size(12.dp)
                                    )
                                }
                            }
                        }
                        if (!item.entry.allDay) {
                            Spacer(modifier = GlanceModifier.width(8.dp))
                            Text(
                                text = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(item.entry.startMillis!!)),
                                style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurfaceVariant)
                            )
                        }
                    }
                    if (showEventDetails && !item.entry.description.isNullOrBlank()) {
                        Text(
                            text = item.entry.description,
                            maxLines = 2,
                            style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.outline)
                        )
                    }
                    if (item.tagNames.isNotEmpty()) {
                        TagChipsRow(item.tagNames)
                    }
                }
                Spacer(modifier = GlanceModifier.height(2.dp))
            }
            if (nowSplitIndex == items.size) {
                NowSplitterRow()
                val (leading, trailing) = nothingElseTodayEmojis(LocalTime.now().hour)
                Text(
                    text = "$leading ${languageManager.getString("nothing_else_today")} $trailing",
                    style = TextStyle(
                        fontSize = 12.sp,
                        color = GlanceTheme.colors.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    ),
                    modifier = GlanceModifier.fillMaxWidth().padding(vertical = 4.dp)
                )
            }
    }
}
private const val ROW_TINT_ALPHA = 0.18f

/** Glance-compatible equivalent of [com.voxapps.calendarapp.ui.todo.ToDoNodeTimeline]'s `NowSplitter`
 *  — a small dot + line + current time, marking "now" within today's event list. Glance has no
 *  Canvas/Path drawing, so this uses a thin background-colored Box for the line instead of a real
 *  divider draw call. */
@Composable
private fun NowSplitterRow() {
    val color = GlanceTheme.colors.error
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically
    ) {
        Box(modifier = GlanceModifier.size(6.dp).cornerRadius(3.dp).background(color)) {}
        Spacer(modifier = GlanceModifier.width(4.dp))
        Box(modifier = GlanceModifier.defaultWeight().height(1.5.dp).background(color)) {}
        Spacer(modifier = GlanceModifier.width(6.dp))
        Text(
            text = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date()),
            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = color)
        )
    }
}

@Composable
private fun TagChipsRow(tagNames: List<String>) {
    Row(modifier = GlanceModifier.fillMaxWidth().padding(top = 2.dp)) {
        tagNames.take(3).forEach { tag ->
            Box(
                modifier = GlanceModifier
                    .cornerRadius(6.dp)
                    .background(GlanceTheme.colors.secondaryContainer)
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            ) {
                Text(
                    text = tag,
                    maxLines = 1,
                    style = TextStyle(fontSize = 10.sp, color = GlanceTheme.colors.onSecondaryContainer)
                )
            }
            Spacer(modifier = GlanceModifier.width(4.dp))
        }
    }
}

private fun dayLabel(date: LocalDate, today: LocalDate, languageManager: LanguageManager, locale: Locale): String {
    val shortDate = WidgetDayFormats.short(date, locale)
    return when (date) {
        today -> "${languageManager.getString("widget_up_next")} (${languageManager.getString("today")}, $shortDate)"
        today.plusDays(1) -> "${languageManager.getString("tomorrow")} - $shortDate"
        else -> WidgetDayFormats.weekday(date, locale)
    }
}

/** The day heading for this widget: its own wording, the shared presentation. */
@Composable
private fun DaySeparatorLabel(date: LocalDate, today: LocalDate, languageManager: LanguageManager, locale: Locale) {
    DaySeparatorLabel(
        text = dayLabel(date, today, languageManager, locale),
        isToday = date == today,
        style = DaySeparatorStyle.Pill
    )
}
