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
import androidx.glance.unit.ColorProvider
import com.voxapps.attachments.VisionAttachmentCapture
import com.voxapps.design.toEnumOr
import com.voxapps.expenses.ExpensesActivity
import androidx.compose.runtime.produceState
import com.voxapps.expenses.domain.budget.BudgetHeadline
import com.voxapps.expenses.ExpensesApplication
import com.voxapps.expenses.R
import com.voxapps.expenses.data.ExpenseWithDetails
import com.voxapps.expenses.data.ExpensesAttachments
import com.voxapps.expenses.data.TransactionDirection
import com.voxapps.expenses.domain.llm.LlmTasks
import com.voxapps.expenses.domain.localization.LanguageManager
import com.voxapps.expenses.state.ExpensesUiState
import com.voxapps.expenses.ui.CategoryColors
import com.voxapps.expenses.ui.formatAmount
import com.voxapps.design.effects.TodayEffect
import com.voxapps.design.effects.TodayEffectStyle
import com.voxapps.design.showRequirementToast
import com.voxapps.ipc.VoxAppsDiscovery
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxOcrRequest
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.voxapps.widget.VoxWidgetScaffold
import com.voxapps.widget.WidgetDayCards
import com.voxapps.widget.WidgetDayChrome
import com.voxapps.widget.WidgetScanRow
import com.voxapps.widget.WidgetDayFormats
import com.voxapps.widget.DaySeparatorStyle
import com.voxapps.widget.DaySeparatorLabel

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

        val addIntent = Intent(context, ExpensesActivity::class.java).apply {
            putExtra(ExpensesActivity.EXTRA_QUICK_ADD, true)
        }
        val openAppIntent = Intent(context, ExpensesActivity::class.java)
        val scanEnabled = VoxAppsDiscovery.isAppInstalled(context, VoxIpc.VISION_PACKAGE) &&
            VoxAppsDiscovery.isCommanderInstalled(context)

        // Every dynamic value is collected INSIDE the composition, never read into a val out here:
        // provideGlance runs once per Glance session, while an updateAll() on a live session only
        // RECOMPOSES the content lambda — data captured out here would be redrawn verbatim forever,
        // which is exactly how the widget used to freeze one insert behind until the session was
        // rebuilt by a process death or launcher restart. As composition state, each flow emission
        // recomposes with fresh data and Glance republishes the RemoteViews.
        provideContent {
            val uiState by container.expensesStateManager.uiState.collectAsState()
            val allExpenses by container.expensesRepository.expensesWithDetails
                .collectAsState(initial = emptyList())
            val attachedExpenseIds by remember {
                container.attachmentDao.observeRecordIdsWithAttachments(ExpensesAttachments.RECORD_TYPE)
            }.collectAsState(initial = emptyList())
            // The flow, not getSnapshot() — the cached snapshot is updated by its own independent
            // collector with no ordering guarantee against whatever triggered this recomposition;
            // the collected flow carries the value that caused it.
            val settingsSnapshot by container.settingsRepository.settingsFlow
                .collectAsState(initial = container.settingsRepository.getSnapshot())
            val locale = Locale.forLanguageTag(settingsSnapshot.language)
            val pendingCaptures by container.pendingLlmRequestCount.collectAsState(initial = 0)
            val budgets by container.expensesRepository.accountBudgets.collectAsState(initial = emptyList())
            // The tree, because a payment is filed against the card it was made with and comes out
            // of the account that card reaches.
            val budgetAccounts by container.expensesRepository.bankAccounts.collectAsState(initial = emptyList())
            // Rates are fetched, so a mixed-currency total is worked out off the composition and
            // recomputed when either half changes. A rate that has never been fetched leaves its
            // budget out rather than adding it as though it were already home currency.
            val budgetLine by produceState<BudgetHeadline.Line?>(null, budgets, budgetAccounts, allExpenses, settingsSnapshot) {
                value = BudgetHeadline.of(
                    settings = settingsSnapshot,
                    budgets = budgets,
                    expenses = allExpenses.map { it.expense },
                    accounts = budgetAccounts,
                    convert = { amount, from ->
                        container.exchangeRateRepository.convertToHome(amount, from, settingsSnapshot.homeCurrency)
                    }
                )
            }
            val recentExpenses = if (uiState is ExpensesUiState.Unlocked) allExpenses else emptyList()

            GlanceTheme {
                ExpensesWidgetContent(
                    pendingCaptures = if (uiState is ExpensesUiState.Locked) 0 else pendingCaptures,
                    budgetLine = budgetLine.takeIf { uiState !is ExpensesUiState.Locked },
                    locked = uiState is ExpensesUiState.Locked,
                    expenses = recentExpenses,
                    attachedExpenseIds = attachedExpenseIds.toSet(),
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

// Glance/RemoteViews has no expand-in-place speed dial like a real Compose FAB can render (see
// core:design's SpeedDialFab, used everywhere else) — the widget instead shows 3 small static icons
// (single/stitch/batch), each its own ActionCallback sharing this one gated launch helper.
private suspend fun runWidgetScan(context: Context, captureMode: String) {
    val container = (context.applicationContext as ExpensesApplication).container
    val languageManager = container.languageManager
    when {
        !VoxAppsDiscovery.isAppInstalled(context, VoxIpc.VISION_PACKAGE) ->
            showRequirementToast(context, languageManager.getString("vision_required_message"))
        !VoxAppsDiscovery.isCommanderInstalled(context) ->
            showRequirementToast(context, languageManager.getString("commander_required_message"))
        else -> VisionAttachmentCapture.launch(
            context, "${LlmTasks.EXPENSE_SCAN_CLEANUP}:pending-create", null, produceOCR = true, captureMode = captureMode, tableMode = true
        )
    }
}

class ExpensesWidgetScanSingleAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        runWidgetScan(context, VoxOcrRequest.CAPTURE_MODE_SINGLE)
}

class ExpensesWidgetScanStitchAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        runWidgetScan(context, VoxOcrRequest.CAPTURE_MODE_STITCH)
}

class ExpensesWidgetScanBatchAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        runWidgetScan(context, VoxOcrRequest.CAPTURE_MODE_BATCH)
}

@Composable
private fun ExpensesWidgetContent(
    locked: Boolean,
    expenses: List<ExpenseWithDetails>,
    attachedExpenseIds: Set<Long>,
    budgetLine: BudgetHeadline.Line?,
    pendingCaptures: Int,
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
    VoxWidgetScaffold(
        title = languageManager.getString("widget_app_name"),
        openAppAction = actionStartActivity(openAppIntent),
        addButtonText = languageManager.getString("widget_add_button"),
        addAction = actionStartActivity(addIntent),
        locked = locked,
        lockedText = languageManager.getString("locked_title"),
        scan = WidgetScanRow(
            enabled = scanEnabled,
            singleAction = actionRunCallback<ExpensesWidgetScanSingleAction>(),
            stitchAction = actionRunCallback<ExpensesWidgetScanStitchAction>(),
            batchAction = actionRunCallback<ExpensesWidgetScanBatchAction>(),
            singleDescription = languageManager.getString("capture_mode_single"),
            stitchDescription = languageManager.getString("capture_mode_stitch"),
            batchDescription = languageManager.getString("capture_mode_batch")
        )
    ) {
        // Work in progress before anything else: a capture waiting for an answer is the reason a
        // record you expected is not in the list yet.
        if (pendingCaptures > 0) {
            Text(
                text = languageManager.getString("pending_captures").format(pendingCaptures),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
                modifier = GlanceModifier.padding(bottom = 4.dp)
            )
        }
        // Above the list, because it is the summary the list is of. Absent entirely when the
        // setting says off — see BudgetHeadline.
        budgetLine?.let { line ->
            Text(
                text = languageManager.getString("widget_budget_left")
                    .format("%.2f %s".format(line.remaining, line.currency)),
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                ),
                modifier = GlanceModifier.padding(bottom = 6.dp)
            )
        }
        RecentExpensesList(
            expenses, attachedExpenseIds, languageManager, locale, borderEnabled, borderThicknessDp, borderColor,
            todayEffect, todayEffectStyle, todayEffectColor
        )
    }
}

@Composable
private fun RecentExpensesList(
    expenses: List<ExpenseWithDetails>,
    attachedExpenseIds: Set<Long>,
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
    val grouped = recent
        .groupBy { Instant.ofEpochMilli(it.expense.dateTime).atZone(zoneId).toLocalDate() }
        .toList()
        .toMutableList()

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

    val context = LocalContext.current

    WidgetDayCards(
        groups = grouped,
        today = today,
        chrome = chrome,
        emptyDayText = languageManager.getString("widget_nothing_today"),
        dayLabel = { date -> dayLabel(date, today, languageManager, locale) }
    ) { _, dayItems ->
        dayItems.forEach { item ->
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
            Row(modifier = GlanceModifier.defaultWeight(), verticalAlignment = Alignment.Vertical.CenterVertically) {
                // Text, which is the whole reason the icon is stored as text: a widget renders no
                // vectors of its own, and a name pointing into a drawable set would point at nothing
                // here.
                item.category?.icon?.let { icon ->
                    Text(
                        text = icon,
                        style = TextStyle(fontSize = 14.sp),
                        modifier = GlanceModifier.padding(end = 4.dp)
                    )
                }
                Text(
                    text = item.expense.title?.takeIf { it.isNotBlank() } ?: item.expense.vendor ?: "—",
                    maxLines = 1,
                    style = TextStyle(fontSize = 15.sp, color = GlanceTheme.colors.onSurface),
                    modifier = GlanceModifier.defaultWeight()
                )
                if (item.expense.id in attachedExpenseIds) {
                    Spacer(modifier = GlanceModifier.width(4.dp))
                    Image(
                        provider = ImageProvider(R.drawable.ic_attachment),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
                        modifier = GlanceModifier.size(12.dp)
                    )
                }
            }
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

private const val ROW_TINT_ALPHA = 0.18f

private fun dayLabel(date: LocalDate, today: LocalDate, languageManager: LanguageManager, locale: Locale): String {
    val shortDate = WidgetDayFormats.short(date, locale)
    return when (date) {
        today -> "${languageManager.getString("today")}, $shortDate"
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
