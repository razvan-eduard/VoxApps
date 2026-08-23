package com.voxapps.expenses.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.voxapps.expenses.data.Category
import com.voxapps.expenses.data.ExchangeRateRepository
import com.voxapps.expenses.data.ExpenseWithDetails
import com.voxapps.expenses.data.TransactionDirection
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

private enum class ReportPeriod { WEEK, MONTH, YEAR, ALL_TIME }

private data class CategoryTotal(val category: Category?, val amount: Double)

/**
 * Textual/tabular spend report (no charting library decision made yet — see
 * `docs/BUILD_TIME_DEPENDENCIES.md`-style deferral precedent elsewhere in this repo; a follow-up can
 * swap this for a chart without changing the underlying data). Converts each expense's own currency
 * into the home currency via [ExchangeRateRepository] (Stage 5's cached-rate infrastructure) before
 * summing, so mixed-currency expenses roll into one meaningful total.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(
    expenses: List<ExpenseWithDetails>,
    homeCurrency: String,
    exchangeRateRepository: ExchangeRateRepository,
    onBack: () -> Unit
) {
    val languageManager = LocalLanguageManager.current

    // System back mirrors the top-bar arrow: return to the expense list.
    BackHandler { onBack() }

    var period by remember { mutableStateOf(ReportPeriod.MONTH) }
    var loading by remember { mutableStateOf(true) }
    var totalOutgoing by remember { mutableStateOf(0.0) }
    var totalIncoming by remember { mutableStateOf(0.0) }
    var byCategory by remember { mutableStateOf<List<CategoryTotal>>(emptyList()) }

    LaunchedEffect(period, expenses, homeCurrency) {
        loading = true
        val today = LocalDate.now()
        val windowStart = when (period) {
            ReportPeriod.WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            ReportPeriod.MONTH -> today.withDayOfMonth(1)
            ReportPeriod.YEAR -> today.withDayOfYear(1)
            ReportPeriod.ALL_TIME -> null
        }?.atStartOfDay(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()

        val inRange = expenses.filter { windowStart == null || it.expense.dateTime >= windowStart }
        val converted = inRange.mapNotNull { ewd ->
            val amount = exchangeRateRepository.convertToHome(ewd.expense.totalAmount, ewd.expense.currencyCode, homeCurrency)
            if (amount == null) null else Triple(ewd.expense.direction, ewd.category, amount)
        }
        totalOutgoing = converted.filter { it.first == TransactionDirection.OUTGOING }.sumOf { it.third }
        totalIncoming = converted.filter { it.first == TransactionDirection.INCOMING }.sumOf { it.third }
        byCategory = converted
            .filter { it.first == TransactionDirection.OUTGOING }
            .groupBy { it.second }
            .map { (category, entries) -> CategoryTotal(category, entries.sumOf { it.third }) }
            .sortedByDescending { it.amount }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(languageManager.getString("reports_title")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = languageManager.getString("back"))
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FilterChip(selected = period == ReportPeriod.WEEK, onClick = { period = ReportPeriod.WEEK }, label = { Text(languageManager.getString("report_period_week")) })
                FilterChip(selected = period == ReportPeriod.MONTH, onClick = { period = ReportPeriod.MONTH }, label = { Text(languageManager.getString("report_period_month")) })
                FilterChip(selected = period == ReportPeriod.YEAR, onClick = { period = ReportPeriod.YEAR }, label = { Text(languageManager.getString("report_period_year")) })
                FilterChip(selected = period == ReportPeriod.ALL_TIME, onClick = { period = ReportPeriod.ALL_TIME }, label = { Text(languageManager.getString("report_period_all_time")) })
            }

            if (loading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                Text(
                    languageManager.getString("report_total_label"),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 16.dp)
                )
                Text(formatAmount(totalOutgoing, homeCurrency), style = MaterialTheme.typography.headlineMedium)

                if (totalIncoming > 0.0) {
                    Text(
                        languageManager.getString("report_total_received_label"),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    Text(
                        formatAmount(totalIncoming, homeCurrency),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                Text(languageManager.getString("report_by_category_label"), style = MaterialTheme.typography.labelLarge)

                if (byCategory.isEmpty()) {
                    Text(
                        languageManager.getString("no_expenses_yet"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                } else {
                    val max = byCategory.maxOf { it.amount }.coerceAtLeast(0.01)
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(byCategory) { entry ->
                            val label = entry.category?.labelled() ?: languageManager.getString("none")
                            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(label, style = MaterialTheme.typography.bodyMedium)
                                    Text(formatAmount(entry.amount, homeCurrency), style = MaterialTheme.typography.bodyMedium)
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .padding(top = 4.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth((entry.amount / max).toFloat().coerceIn(0f, 1f))
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(
                                                entry.category?.colorArgb?.let { CategoryColors.fromStored(it) }
                                                    ?: MaterialTheme.colorScheme.primary
                                            )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
