package com.voxapps.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
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
import java.time.LocalDate

/**
 * The frame every Vox records widget shares: title header with the three scan-mode icons, a
 * locked placeholder, the content slot, and the bottom "+ add" button. Three widgets drew this
 * byte-identically before it lived here; what an app owns is its rows, its intents/actions, and
 * its strings — resolved text arrives as parameters, since `:core:widget` has no LanguageManager.
 */

/** The header's three scan-mode icons. [enabled] only recolors — a tap on a dimmed icon still
 *  fires its action, whose app-side handler explains what's missing via toast. Each mode keeps
 *  its color (red/single, yellow/stitch, green/batch) so the three are distinguishable at a
 *  glance; Glance has no alpha modifier, so a muted tint stands in for "disabled". */
class WidgetScanRow(
    val enabled: Boolean,
    val singleAction: Action,
    val stitchAction: Action,
    val batchAction: Action,
    val singleDescription: String,
    val stitchDescription: String,
    val batchDescription: String
)

@Composable
fun VoxWidgetScaffold(
    title: String,
    openAppAction: Action,
    locked: Boolean,
    lockedText: String,
    scan: WidgetScanRow?,
    // Both null = no bottom add button (a widget whose content is itself the add surface).
    addButtonText: String? = null,
    addAction: Action? = null,
    content: @Composable () -> Unit
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
                .clickable(openAppAction),
            verticalAlignment = Alignment.Vertical.CenterVertically
        ) {
            Text(
                text = title,
                style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp, color = GlanceTheme.colors.onSurface)
            )
            Spacer(modifier = GlanceModifier.defaultWeight())
            if (scan != null) {
                val disabledTint = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant)
                val singleTint = if (scan.enabled) ColorFilter.tint(androidx.glance.unit.ColorProvider(Color(0xFFE53935))) else disabledTint
                val stitchTint = if (scan.enabled) ColorFilter.tint(androidx.glance.unit.ColorProvider(Color(0xFFFBC02D))) else disabledTint
                val batchTint = if (scan.enabled) ColorFilter.tint(androidx.glance.unit.ColorProvider(Color(0xFF43A047))) else disabledTint
                Image(
                    provider = ImageProvider(R.drawable.ic_scan),
                    contentDescription = scan.singleDescription,
                    colorFilter = singleTint,
                    modifier = GlanceModifier.size(25.dp).clickable(scan.singleAction)
                )
                Spacer(modifier = GlanceModifier.width(6.dp))
                Image(
                    provider = ImageProvider(R.drawable.ic_stitch),
                    contentDescription = scan.stitchDescription,
                    colorFilter = stitchTint,
                    modifier = GlanceModifier.size(25.dp).clickable(scan.stitchAction)
                )
                Spacer(modifier = GlanceModifier.width(6.dp))
                Image(
                    provider = ImageProvider(R.drawable.ic_batch),
                    contentDescription = scan.batchDescription,
                    colorFilter = batchTint,
                    modifier = GlanceModifier.size(25.dp).clickable(scan.batchAction)
                )
            }
        }

        Spacer(modifier = GlanceModifier.height(8.dp))

        Box(modifier = GlanceModifier.defaultWeight()) {
            if (locked) {
                Text(
                    text = lockedText,
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant)
                )
            } else {
                content()
            }
        }

        if (addButtonText != null && addAction != null) {
            WidgetAddButton(text = addButtonText, addAction = addAction)
        }
    }
}

/** Full-width, bordered "+ X" button pinned to the widget's bottom edge — the manual add entry
 *  point. Glance has no dedicated border modifier, so the border is a slightly larger, differently
 *  colored outer Box behind a slightly inset, differently colored inner Box. */
@Composable
private fun WidgetAddButton(text: String, addAction: Action) {
    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .cornerRadius(10.dp)
            .background(GlanceTheme.colors.primary)
            .clickable(addAction)
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

/**
 * How the day cards dress: an optional border around non-today days, and a static rendering of
 * the app's today-effect settings (widgets/RemoteViews can't animate the in-app pulse) — a ring
 * in [todayColor] and/or a background tint. The app maps its own settings enums onto these
 * resolved booleans/colors, so this module needs no dependency on where those settings live.
 */
data class WidgetDayChrome(
    val borderEnabled: Boolean = false,
    val borderThicknessDp: Int = 0,
    val borderColor: Color = Color.Transparent,
    val todayRing: Boolean = false,
    val todayBackground: Boolean = false,
    val todayColor: Color = Color.Transparent
)

private const val TODAY_BACKGROUND_TINT_ALPHA = 0.22f

/**
 * The day-grouped list every records widget renders: one card per day with a
 * [DaySeparatorLabel] heading, chrome per [WidgetDayChrome], and the app's own rows inside.
 * Grouping/ordering stays with the caller — which days appear, in which order, what today pins
 * to, is each widget's semantics ("recent" vs "upcoming"); this renders whatever arrives, in
 * the order it arrives. [dayItemsContent] receives the whole day's items rather than one at a
 * time, so a widget can interleave its own furniture between rows (the calendar's now-splitter).
 */
@Composable
fun <T> WidgetDayCards(
    groups: List<Pair<LocalDate, List<T>>>,
    today: LocalDate,
    chrome: WidgetDayChrome,
    emptyDayText: String,
    dayLabel: (LocalDate) -> String,
    dayItemsContent: @Composable (LocalDate, List<T>) -> Unit
) {
    LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
        items(groups, itemId = { it.first.toEpochDay() }) { (date, items) ->
            val isToday = date == today
            val showTodayRing = isToday && chrome.todayRing
            val showTodayBackground = isToday && chrome.todayBackground
            val gap = if ((chrome.borderEnabled && !isToday) || showTodayRing) (8 + chrome.borderThicknessDp * 1.5f).dp else 8.dp

            val dayContent: @Composable () -> Unit = {
                DaySeparatorLabel(text = dayLabel(date), isToday = isToday, style = DaySeparatorStyle.Pill)
                if (items.isEmpty()) {
                    Text(
                        text = emptyDayText,
                        style = TextStyle(
                            fontSize = 13.sp,
                            color = GlanceTheme.colors.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        ),
                        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 8.dp)
                    )
                } else {
                    dayItemsContent(date, items)
                }
            }

            when {
                (chrome.borderEnabled && !isToday) || showTodayRing -> {
                    // Bordered card: historical days use borderColor, today (when the effect's
                    // style calls for a ring) uses todayColor instead.
                    val ringColor = if (showTodayRing) chrome.todayColor else chrome.borderColor
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
                                    .padding(chrome.borderThicknessDp.dp)
                            ) {
                                Column(
                                    modifier = GlanceModifier
                                        .fillMaxWidth()
                                        .cornerRadius(10.dp)
                                        .let { m ->
                                            if (showTodayBackground) {
                                                m.background(chrome.todayColor.copy(alpha = TODAY_BACKGROUND_TINT_ALPHA))
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
                            .background(chrome.todayColor.copy(alpha = TODAY_BACKGROUND_TINT_ALPHA))
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
