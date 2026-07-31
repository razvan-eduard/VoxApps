package com.voxapps.expenses.ui.widget

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
import com.voxapps.expenses.ExpensesActivity
import com.voxapps.expenses.ExpensesApplication
import com.voxapps.expenses.R
import com.voxapps.expenses.data.ExpenseWithDetails
import com.voxapps.expenses.data.TransactionDirection
import com.voxapps.expenses.domain.llm.ExpenseScanRequestSender
import com.voxapps.expenses.domain.localization.LanguageManager
import com.voxapps.expenses.state.ExpensesUiState
import com.voxapps.expenses.ui.CategoryColors
import com.voxapps.expenses.ui.formatAmount
import com.voxapps.design.effects.TodayEffect
import com.voxapps.design.effects.TodayEffectStyle
import com.voxapps.design.showRequirementToast
import com.voxapps.ipc.VoxAppsDiscovery
import com.voxapps.ipc.VoxIpc
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Home-screen widget: a snapshot of recent expenses plus quick "Add"/"Scan" actions — lives
 * entirely inside vox-expenses (not centralized in Commander, mirrors vox-calendar's
 * CalendarWidget). The list is read directly from [com.voxapps.expenses.data.ExpensesRepository]
 * rather than [ExpensesUiState.Unlocked.expenses] on purpose: that field is already filtered by
 * whatever category/date/bank/vendor filter happens to be active in the foreground UI, which would
 * make the widget's "recent expenses" snapshot silently depend on unrelated in-app UI state — the
 * widget only borrows [ExpensesUiState] to decide Locked vs Unlocked (the same biometric-lock check
 * [com.voxapps.expenses.ui.ExpensesRoot] uses), not its filtered expense list.
 */
class ExpensesWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = (context.applicationContext as ExpensesApplication).container

        val uiState = container.expensesStateManager.uiState
            .filterNot { it is ExpensesUiState.Loading }
            .first()

        val recentExpenses = if (uiState is ExpensesUiState.Unlocked) {
            container.expensesRepository.expensesWithDetails.first()
        } else {
            emptyList()
        }

        val addIntent = Intent(context, ExpensesActivity::class.java).apply {
            putExtra(ExpensesActivity.EXTRA_QUICK_ADD, true)
        }
        val openAppIntent = Intent(context, ExpensesActivity::class.java)
        // Read the live flow, not getSnapshot() — that cached value is updated by its own
        // independent collector, racing against the collector that triggers this very redraw
        // (ExpensesContainer's combine()). Both react to the same DataStore write with no ordering
        // guarantee between them, so getSnapshot() could still return the previous value the instant
        // this redraw fires — a settings change (e.g. picking a new today-effect) would then render
        // one generation stale until something else happened to trigger a second redraw. A direct
        // flow read has no such race.
        val settingsSnapshot = container.settingsRepository.settingsFlow.first()
        val locale = Locale.forLanguageTag(settingsSnapshot.language)

        val scanEnabled = VoxAppsDiscovery.isAppInstalled(context, VoxIpc.VISION_PACKAGE) &&
            VoxAppsDiscovery.isCommanderInstalled(context)

        provideContent {
            GlanceTheme {
                ExpensesWidgetContent(
                    locked = uiState is ExpensesUiState.Locked,
                    expenses = recentExpenses,
                    languageManager = container.languageManager,
                    addIntent = addIntent,
                    openAppIntent = openAppIntent,
                    locale = locale,
                    scanEnabled = scanEnabled,
                    borderEnabled = settingsSnapshot.widgetBorderEnabled,
                    borderThicknessDp = settingsSnapshot.widgetBorderThicknessDp,
                    borderColor = Color(settingsSnapshot.widgetBorderColorArgb.toInt()),
                    // todayEffectShowInWidget is a widget-only opt-out, independent of the in-app
                    // effect — collapsing it to NONE here reuses the existing effect==NONE gate
                    // below with no signature changes (mirrors CalendarWidget.kt).
                    todayEffect = if (settingsSnapshot.todayEffectShowInWidget) {
                        runCatching { TodayEffect.valueOf(settingsSnapshot.todayEffect) }.getOrDefault(TodayEffect.NONE)
                    } else {
                        TodayEffect.NONE
                    },
                    todayEffectStyle = runCatching { TodayEffectStyle.valueOf(settingsSnapshot.todayEffectStyle) }.getOrDefault(TodayEffectStyle.RING),
                    todayEffectColor = Color(settingsSnapshot.todayEffectColor.toInt())
                )
            }
        }
    }
}

class ExpensesWidgetScanAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val container = (context.applicationContext as ExpensesApplication).container
        val languageManager = container.languageManager
        when {
            !VoxAppsDiscovery.isAppInstalled(context, VoxIpc.VISION_PACKAGE) ->
                showRequirementToast(context, languageManager.getString("vision_required_message"))
            !VoxAppsDiscovery.isCommanderInstalled(context) ->
                showRequirementToast(context, languageManager.getString("commander_required_message"))
            else -> ExpenseScanRequestSender.send(context)
        }
    }
}

@Composable
private fun ExpensesWidgetContent(
    locked: Boolean,
    expenses: List<ExpenseWithDetails>,
    languageManager: LanguageManager,
    addIntent: Intent,
    openAppIntent: Intent,
    locale: Locale,
    scanEnabled: Boolean,
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
            Image(
                provider = ImageProvider(R.drawable.ic_scan),
                contentDescription = languageManager.getString("scan_receipt"),
                // Dimmed (not hidden) when Vision/Commander aren't installed — tapping it still
                // works, ExpensesWidgetScanAction shows an explanatory toast instead of launching
                // Vision; Glance has no alpha modifier, so a muted tint stands in for "disabled".
                colorFilter = ColorFilter.tint(if (scanEnabled) GlanceTheme.colors.primary else GlanceTheme.colors.onSurfaceVariant),
                modifier = GlanceModifier
                    .size(18.dp)
                    .clickable(actionRunCallback<ExpensesWidgetScanAction>())
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
                RecentExpensesList(
                    expenses, languageManager, locale, borderEnabled, borderThicknessDp, borderColor,
                    todayEffect, todayEffectStyle, todayEffectColor
                )
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
private fun RecentExpensesList(
    expenses: List<ExpenseWithDetails>,
    languageManager: LanguageManager,
    locale: Locale,
    borderEnabled: Boolean,
    borderThicknessDp: Int,
    borderColor: Color,
    todayEffect: TodayEffect,
    todayEffectStyle: TodayEffectStyle,
    todayEffectColor: Color
) {
    val zoneId = ZoneId.systemDefault()
    val today = LocalDate.now(zoneId)
    val recent = expenses
        .sortedByDescending { it.expense.dateTime }
        .take(20)

    // groupBy preserves first-seen key order, and recent is already sorted newest-first, so the
    // resulting day groups come out in reverse-chronological order for free.
    val grouped = recent.groupBy { Instant.ofEpochMilli(it.expense.dateTime).atZone(zoneId).toLocalDate() }.toMutableMap()

    // Ensure Today is always present as the first entry
    if (!grouped.containsKey(today)) {
        val newGrouped = mutableMapOf(today to emptyList<ExpenseWithDetails>())
        newGrouped.putAll(grouped)
        grouped.clear()
        grouped.putAll(newGrouped)
    }

    val context = LocalContext.current

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
                    items.forEach { item ->
                        val editIntent = Intent(context, ExpensesActivity::class.java).apply {
                            putExtra(VoxIpc.EXTRA_EXPENSE_ID, item.expense.id)
                        }
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
                            Text(
                                text = item.expense.title?.takeIf { it.isNotBlank() } ?: item.expense.vendor ?: "—",
                                maxLines = 1,
                                style = TextStyle(fontSize = 15.sp, color = GlanceTheme.colors.onSurface),
                                modifier = GlanceModifier.defaultWeight()
                            )
                            if (!item.expense.comments.isNullOrBlank()) {
                                Spacer(modifier = GlanceModifier.width(8.dp))
                                Text(
                                    text = item.expense.comments,
                                    maxLines = 2,
                                    style = TextStyle(
                                        fontSize = 12.sp,
                                        color = GlanceTheme.colors.outline,
                                        textAlign = TextAlign.End
                                    ),
                                    modifier = GlanceModifier.defaultWeight()
                                )
                            }
                            Spacer(modifier = GlanceModifier.width(8.dp))
                            Image(
                                provider = ImageProvider(
                                    if (item.expense.direction == TransactionDirection.INCOMING) {
                                        R.drawable.ic_arrow_inward
                                    } else {
                                        R.drawable.ic_arrow_outward
                                    }
                                ),
                                contentDescription = null,
                                modifier = GlanceModifier.size(13.dp)
                            )
                            Spacer(modifier = GlanceModifier.width(4.dp))
                            Text(
                                text = formatAmount(item.expense.totalAmount, item.expense.currencyCode),
                                style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurfaceVariant)
                            )
                        }
                        Spacer(modifier = GlanceModifier.height(2.dp))
                    }
                }
            }

            when {
                (borderEnabled && !isToday) || showTodayRing -> {
                    // Bordered card: historical days use borderColor, today (when the today-effect's
                    // style calls for a ring) uses todayEffectColor instead.
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

private fun dayLabel(date: LocalDate, today: LocalDate, languageManager: LanguageManager, locale: Locale): String {
    val shortDate = date.format(DateTimeFormatter.ofPattern("d MMM", locale))
    return when (date) {
        today -> "${languageManager.getString("today")}, $shortDate"
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
