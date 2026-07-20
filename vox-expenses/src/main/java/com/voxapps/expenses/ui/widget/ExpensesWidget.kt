package com.voxapps.expenses.ui.widget

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
import com.voxapps.expenses.domain.llm.ExpenseScanRequestSender
import com.voxapps.expenses.domain.localization.LanguageManager
import com.voxapps.expenses.state.ExpensesUiState
import com.voxapps.expenses.ui.CategoryColors
import com.voxapps.expenses.ui.formatAmount
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
        val locale = Locale.forLanguageTag(container.settingsRepository.getSnapshot().language)

        provideContent {
            GlanceTheme {
                ExpensesWidgetContent(
                    locked = uiState is ExpensesUiState.Locked,
                    expenses = recentExpenses,
                    languageManager = container.languageManager,
                    addIntent = addIntent,
                    locale = locale
                )
            }
        }
    }
}

class ExpensesWidgetScanAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        ExpenseScanRequestSender.send(context)
    }
}

@Composable
private fun ExpensesWidgetContent(
    locked: Boolean,
    expenses: List<ExpenseWithDetails>,
    languageManager: LanguageManager,
    addIntent: Intent,
    locale: Locale
) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.surface)
            .padding(12.dp)
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
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
                colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
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
                RecentExpensesList(expenses, languageManager, locale)
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
private fun RecentExpensesList(expenses: List<ExpenseWithDetails>, languageManager: LanguageManager, locale: Locale) {
    val zoneId = ZoneId.systemDefault()
    val today = LocalDate.now(zoneId)
    val recent = expenses
        .sortedByDescending { it.expense.dateTime }
        .take(6)

    if (recent.isEmpty()) {
        Text(
            text = languageManager.getString("widget_no_recent_expenses"),
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant)
        )
        return
    }

    // groupBy preserves first-seen key order, and recent is already sorted newest-first, so the
    // resulting day groups come out in reverse-chronological order for free.
    val grouped = recent.groupBy { Instant.ofEpochMilli(it.expense.dateTime).atZone(zoneId).toLocalDate() }
    val context = LocalContext.current

    Column {
        grouped.forEach { (date, items) ->
            val isToday = date == today
            DaySeparatorLabel(date, today, languageManager, locale)
            Box(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .height(if (isToday) 2.dp else 1.dp)
                    .background(if (isToday) GlanceTheme.colors.primary else GlanceTheme.colors.outline)
            ) {}

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
                    Text(
                        text = formatAmount(item.expense.totalAmount, item.expense.currencyCode),
                        style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurfaceVariant)
                    )
                }
                Spacer(modifier = GlanceModifier.height(2.dp))
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
