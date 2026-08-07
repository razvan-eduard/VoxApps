package com.voxapps.calendarapp.ui.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.first
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

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

        // CalendarStateManager's uiState combine() chain is launched eagerly in its own init block
        // (see CalendarStateManager.kt), so it's always live — but starts at Loading for the first
        // few ms after a cold process start (e.g. the OS waking this app just to render the
        // widget). Wait for the first real value instead of racing it.
        val uiState = container.calendarStateManager.uiState
            .filterNot { it is CalendarUiState.Loading }
            .first()

        // Read directly from CalendarRepository rather than CalendarUiState.Unlocked.entries on
        // purpose: that field is already filtered by whatever layer-visibility/tag filter happens to
        // be active in the foreground UI (see CalendarFilter.apply in CalendarStateManager), which
        // made the widget's "upcoming entries" snapshot silently depend on unrelated in-app filter
        // state — a newly added entry on a currently-hidden layer looked like the widget "not
        // refreshing" when it actually had refreshed, just filtered the entry back out. Mirrors
        // ExpensesWidget's identical fix for the same class of bug.
        val entries = container.calendarRepository.entriesWithTags.first()
        val attachedEntryIds = container.attachmentDao
            .getRecordIdsWithAttachments(CalendarAttachments.RECORD_TYPE).toSet()

        val addIntent = Intent(context, CalendarActivity::class.java).apply {
            putExtra(CalendarActivity.EXTRA_QUICK_ADD, true)
        }
        val openAppIntent = Intent(context, CalendarActivity::class.java)
        // Read the live flow, not getSnapshot() — that cached value is updated by its own
        // independent collector (see CalendarSettingsRepositoryImpl), racing against the collector
        // that triggers this very redraw (CalendarContainer's combine()). Both react to the same
        // DataStore write with no ordering guarantee between them, so getSnapshot() could still
        // return the previous value the instant this redraw fires — a settings change (e.g. picking
        // a new today-effect) would then render one generation stale until something else happened
        // to trigger a second redraw. A direct flow read has no such race.
        val settingsSnapshot = container.settingsRepository.settingsFlow.first()
        val locale = Locale.forLanguageTag(settingsSnapshot.language)
        val scanEnabled = VoxAppsDiscovery.isAppInstalled(context, VoxIpc.VISION_PACKAGE) &&
            VoxAppsDiscovery.isCommanderInstalled(context)

        provideContent {
            GlanceTheme {
                CalendarWidgetContent(
                    uiState = uiState,
                    entries = entries,
                    attachedEntryIds = attachedEntryIds,
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
            val disabledTint = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant)
            val singleTint = if (scanEnabled) ColorFilter.tint(ColorProvider(Color(0xFFE53935))) else disabledTint
            val stitchTint = if (scanEnabled) ColorFilter.tint(ColorProvider(Color(0xFFFBC02D))) else disabledTint
            val batchTint = if (scanEnabled) ColorFilter.tint(ColorProvider(Color(0xFF43A047))) else disabledTint
            Image(
                provider = ImageProvider(R.drawable.ic_scan),
                contentDescription = languageManager.getString("capture_mode_single"),
                colorFilter = singleTint,
                modifier = GlanceModifier.size(25.dp).clickable(actionRunCallback<CalendarWidgetScanSingleAction>())
            )
            Spacer(modifier = GlanceModifier.width(6.dp))
            Image(
                provider = ImageProvider(R.drawable.ic_stitch),
                contentDescription = languageManager.getString("capture_mode_stitch"),
                colorFilter = stitchTint,
                modifier = GlanceModifier.size(25.dp).clickable(actionRunCallback<CalendarWidgetScanStitchAction>())
            )
            Spacer(modifier = GlanceModifier.width(6.dp))
            Image(
                provider = ImageProvider(R.drawable.ic_batch),
                contentDescription = languageManager.getString("capture_mode_batch"),
                colorFilter = batchTint,
                modifier = GlanceModifier.size(25.dp).clickable(actionRunCallback<CalendarWidgetScanBatchAction>())
            )
        }

        Spacer(modifier = GlanceModifier.height(8.dp))

        Box(modifier = GlanceModifier.defaultWeight()) {
            when (uiState) {
                is CalendarUiState.Locked -> Text(
                    text = languageManager.getString("locked_title"),
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant)
                )
                is CalendarUiState.Unlocked -> UpcomingEntriesList(
                    entries, uiState.layers, languageManager, locale, showEventDetails,
                    borderEnabled, borderThicknessDp, borderColor,
                    todayEffect, todayEffectStyle, todayEffectColor, attachedEntryIds
                )
                else -> Unit
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
    }.toMutableMap()

    // Ensure Today is always present as the first entry
    if (!grouped.containsKey(today)) {
        val newGrouped = mutableMapOf(today to emptyList<CalendarEntryWithTags>())
        newGrouped.putAll(grouped)
        grouped.clear()
        grouped.putAll(newGrouped)
    }

    LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
        items(grouped.entries.toList(), itemId = { it.key.toEpochDay() }) { (date, items) ->
            val isToday = date == today
            // Widgets (Glance/RemoteViews) can't run the animated pulse the in-app effect uses, so
            // this is a static rendering of the same effect+style+color settings: RING/FULL draw
            // today's card with the same bordered-card treatment other days get (when enabled)
            // colored with todayEffectColor instead of borderColor; BACKGROUND/FULL additionally
            // tint the card's own background with it.
            val showTodayHighlight = isToday && todayEffect != TodayEffect.NONE && todayEffectStyle != TodayEffectStyle.NONE
            val showTodayRing = showTodayHighlight && todayEffectStyle != TodayEffectStyle.BACKGROUND
            val showTodayBackground = showTodayHighlight && todayEffectStyle != TodayEffectStyle.RING
            val gap = if ((borderEnabled && !isToday) || showTodayRing) (8 + borderThicknessDp * 1.5f).dp else 8.dp

            val dayContent: @Composable () -> Unit = {
                DaySeparatorLabel(date, today, languageManager, locale)

                if (items.isEmpty()) {
                    Text(
                        text = languageManager.getString("widget_nothing_today"),
                        style = TextStyle(
                            fontSize = 13.sp,
                            color = GlanceTheme.colors.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        ),
                        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 8.dp)
                    )
                } else {
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

            when {
                (borderEnabled && !isToday) || showTodayRing -> {
                    // Bordered card: historical/future days use borderColor, today (when the
                    // today-effect's style calls for a ring) uses todayEffectColor instead.
                    val ringColor = if (showTodayRing) todayEffectColor else borderColor
                    Box(
                        modifier = GlanceModifier
                            .fillMaxWidth()
                            .padding(bottom = gap)
                    ) {
                        Box(
                            modifier = GlanceModifier
                                .fillMaxWidth()
                                .cornerRadius(12.dp)
                                .background(ringColor)
                        ) {
                            Box(
                                modifier = GlanceModifier
                                    .fillMaxWidth()
                                    .padding(borderThicknessDp.dp)
                            ) {
                                Column(
                                    modifier = GlanceModifier
                                        .fillMaxWidth()
                                        .cornerRadius(10.dp)
                                        .let { m ->
                                            if (showTodayBackground) {
                                                m.background(todayEffectColor.copy(alpha = TODAY_BACKGROUND_TINT_ALPHA))
                                            } else {
                                                m.background(GlanceTheme.colors.surface)
                                            }
                                        }
                                        .padding(8.dp)
                                ) {
                                    dayContent()
                                }
                            }
                        }
                    }
                }
                showTodayBackground -> {
                    // Background-only highlight (today, style = BACKGROUND): no outer ring box.
                    Column(
                        modifier = GlanceModifier
                            .fillMaxWidth()
                            .cornerRadius(10.dp)
                            .background(todayEffectColor.copy(alpha = TODAY_BACKGROUND_TINT_ALPHA))
                            .padding(8.dp)
                            .padding(bottom = gap)
                    ) {
                        dayContent()
                    }
                }
                else -> {
                    // Clean layout: no highlight for today, or border disabled and today-effect off.
                    Column(modifier = GlanceModifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).padding(bottom = gap)) {
                        dayContent()
                    }
                }
            }
        }
    }
}

private const val ROW_TINT_ALPHA = 0.18f
private const val TODAY_BACKGROUND_TINT_ALPHA = 0.22f

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
    val shortDate = date.format(DateTimeFormatter.ofPattern("d MMM", locale))
    return when (date) {
        today -> "${languageManager.getString("widget_up_next")} (${languageManager.getString("today")}, $shortDate)"
        today.plusDays(1) -> "${languageManager.getString("tomorrow")} - $shortDate"
        else -> date.format(DateTimeFormatter.ofPattern("EEE, d MMM", locale))
    }
}

/** Centered day-card header — styled as a prominent "Pill" for Today. */
@Composable
private fun DaySeparatorLabel(date: LocalDate, today: LocalDate, languageManager: LanguageManager, locale: Locale) {
    val isToday = date == today
    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = GlanceModifier
                .let { m ->
                    if (isToday) {
                        m.background(GlanceTheme.colors.primary)
                            .cornerRadius(16.dp)
                            .padding(horizontal = 12.dp, vertical = 2.dp)
                    } else m
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = dayLabel(date, today, languageManager, locale),
                style = TextStyle(
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                    fontSize = if (isToday) 13.sp else 12.sp,
                    color = if (isToday) GlanceTheme.colors.onPrimary else GlanceTheme.colors.primary
                )
            )
        }
    }
}
