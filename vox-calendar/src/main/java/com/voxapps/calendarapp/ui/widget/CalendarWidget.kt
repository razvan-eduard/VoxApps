package com.voxapps.calendarapp.ui.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
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
import com.voxapps.calendarapp.CalendarActivity
import com.voxapps.calendarapp.CalendarApplication
import com.voxapps.calendarapp.R
import com.voxapps.calendarapp.data.CalendarEntryWithTags
import com.voxapps.calendarapp.data.CalendarLayer
import com.voxapps.calendarapp.domain.llm.CalendarScanRequestSender
import com.voxapps.design.showRequirementToast
import com.voxapps.ipc.VoxAppsDiscovery
import com.voxapps.ipc.VoxIpc
import com.voxapps.calendarapp.domain.localization.LanguageManager
import com.voxapps.calendarapp.state.CalendarUiState
import com.voxapps.calendarapp.ui.LayerColors
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.first
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
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

        val addIntent = Intent(context, CalendarActivity::class.java).apply {
            putExtra(CalendarActivity.EXTRA_QUICK_ADD, true)
        }
        val openAppIntent = Intent(context, CalendarActivity::class.java)
        val settingsSnapshot = container.settingsRepository.getSnapshot()
        val locale = Locale.forLanguageTag(settingsSnapshot.language)
        val scanEnabled = VoxAppsDiscovery.isAppInstalled(context, VoxIpc.VISION_PACKAGE) &&
            VoxAppsDiscovery.isCommanderInstalled(context)

        provideContent {
            GlanceTheme {
                CalendarWidgetContent(
                    uiState = uiState,
                    languageManager = container.languageManager,
                    addIntent = addIntent,
                    openAppIntent = openAppIntent,
                    locale = locale,
                    scanEnabled = scanEnabled,
                    showEventDetails = settingsSnapshot.showEventDetailsInWidget,
                    borderEnabled = settingsSnapshot.widgetBorderEnabled,
                    borderThicknessDp = settingsSnapshot.widgetBorderThicknessDp,
                    borderColor = Color(settingsSnapshot.widgetBorderColorArgb.toInt())
                )
            }
        }
    }
}

class CalendarWidgetScanAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val container = (context.applicationContext as CalendarApplication).container
        val languageManager = container.languageManager
        when {
            !VoxAppsDiscovery.isAppInstalled(context, VoxIpc.VISION_PACKAGE) ->
                showRequirementToast(context, languageManager.getString("vision_required_message"))
            !VoxAppsDiscovery.isCommanderInstalled(context) ->
                showRequirementToast(context, languageManager.getString("commander_required_message"))
            else -> CalendarScanRequestSender.send(context)
        }
    }
}

@Composable
private fun CalendarWidgetContent(
    uiState: CalendarUiState,
    languageManager: LanguageManager,
    addIntent: Intent,
    openAppIntent: Intent,
    locale: Locale,
    scanEnabled: Boolean,
    showEventDetails: Boolean,
    borderEnabled: Boolean,
    borderThicknessDp: Int,
    borderColor: Color
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
            Image(
                provider = ImageProvider(R.drawable.ic_scan),
                contentDescription = languageManager.getString("scan_action"),
                colorFilter = ColorFilter.tint(if (scanEnabled) GlanceTheme.colors.primary else GlanceTheme.colors.onSurfaceVariant),
                modifier = GlanceModifier
                    .size(18.dp)
                    .clickable(actionRunCallback<CalendarWidgetScanAction>())
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
                    uiState.entries, uiState.layers, languageManager, locale, showEventDetails,
                    borderEnabled, borderThicknessDp, borderColor
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
    borderColor: Color
) {
    val zoneId = ZoneId.systemDefault()
    val today = LocalDate.now(zoneId)
    // Cutoff is the START of today, not this exact instant — an entry timed earlier today (or an
    // all-day entry, whose startMillis is midnight) must stay visible for the rest of today, not
    // disappear the moment its clock time passes.
    val startOfToday = today.atStartOfDay(zoneId).toInstant().toEpochMilli()
    val upcoming = entries
        .filter { !it.entry.completed && (it.entry.endMillis ?: it.entry.startMillis) >= startOfToday }
        .sortedBy { it.entry.startMillis }
        .take(20)

    if (upcoming.isEmpty()) {
        Text(
            text = languageManager.getString("widget_no_upcoming_entries"),
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant)
        )
        return
    }

    val context = LocalContext.current
    val layerById = layers.associateBy { it.id }
    // groupBy preserves first-seen key order, and upcoming is already sorted by startMillis, so
    // the resulting day groups come out in chronological order for free. Clamp each entry's group
    // date to "today" at the earliest — a multi-day entry that started before today (still shown
    // because it hasn't ended yet, per the filter above) must not produce a day-card dated in the
    // past; it belongs in today's card instead.
    val grouped = upcoming.groupBy { item ->
        val startDate = Instant.ofEpochMilli(item.entry.startMillis).atZone(zoneId).toLocalDate()
        if (startDate.isBefore(today)) today else startDate
    }

    LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
        items(grouped.entries.toList(), itemId = { it.key.toEpochDay() }) { (date, items) ->
            val dayContent: @Composable () -> Unit = {
                DaySeparatorLabel(date, today, languageManager, locale)

                items.forEach { item ->
                    val editIntent = Intent(context, CalendarActivity::class.java).apply {
                        putExtra(CalendarActivity.EXTRA_EDIT_ENTRY_ID, item.entry.id)
                    }
                    val layerColor = layerById[item.entry.layerId]?.let { LayerColors.fromStored(it.colorArgb) }
                    Column(
                        modifier = GlanceModifier
                            .fillMaxWidth()
                            .cornerRadius(6.dp)
                            .let { m -> if (layerColor != null) m.background(layerColor.copy(alpha = ROW_TINT_ALPHA)) else m }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                            .clickable(actionStartActivity(editIntent))
                    ) {
                        Row(
                            modifier = GlanceModifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Vertical.CenterVertically
                        ) {
                            Text(
                                text = item.entry.title,
                                maxLines = 1,
                                style = TextStyle(fontSize = 15.sp, color = GlanceTheme.colors.onSurface),
                                modifier = GlanceModifier.defaultWeight()
                            )
                            if (!item.entry.allDay) {
                                Spacer(modifier = GlanceModifier.width(8.dp))
                                Text(
                                    text = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(item.entry.startMillis)),
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
            }

            if (borderEnabled) {
                // Putting padding and background on the SAME node doesn't create a transparent
                // inset in this Glance/RemoteViews version (verified on-device: the inner
                // background always covers its node's full bounds, padding included, regardless
                // of padding size) — so the padding lives on its own middle layer with no
                // background of its own, between the colored outer Box and the white inner Box.
                Box(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .cornerRadius(12.dp)
                        .background(borderColor)
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
                                .background(GlanceTheme.colors.surface)
                                .padding(8.dp)
                        ) {
                            dayContent()
                        }
                    }
                }
            } else {
                Column(modifier = GlanceModifier.fillMaxWidth().padding(8.dp)) {
                    dayContent()
                }
            }
            Spacer(modifier = GlanceModifier.height(8.dp))
        }
    }
}

private const val ROW_TINT_ALPHA = 0.18f

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
        today -> "${languageManager.getString("today")} - $shortDate"
        today.plusDays(1) -> "${languageManager.getString("tomorrow")} - $shortDate"
        else -> date.format(DateTimeFormatter.ofPattern("EEE, d MMM", locale))
    }
}

/** Centered day-card header — plain text for every day, "Today"/"Tomorrow" only distinguished by
 * a bolder/larger label (the card's own background tint, set by the caller, is what visually
 * separates one day's card from the next). */
@Composable
private fun DaySeparatorLabel(date: LocalDate, today: LocalDate, languageManager: LanguageManager, locale: Locale) {
    val isToday = date == today
    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
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
