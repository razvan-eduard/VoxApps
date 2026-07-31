package com.voxapps.expenses.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.voxapps.calendar.CalendarView
import com.voxapps.design.DoubleBackToExitHandler
import com.voxapps.design.effects.TodayEffect
import com.voxapps.design.effects.TodayEffectStyle
import com.voxapps.design.rememberRequirementGate
import com.voxapps.expenses.data.ExpenseWithDetails
import com.voxapps.expenses.domain.llm.ExpenseScanRequestSender
import com.voxapps.expenses.state.ExpensesStateManager
import com.voxapps.expenses.state.ExpensesUiState
import com.voxapps.expenses.state.SortMode
import com.voxapps.ipc.VoxAppsDiscovery
import com.voxapps.ipc.VoxIpc

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpensesScreen(
    state: ExpensesUiState.Unlocked,
    stateManager: ExpensesStateManager,
    calendarViewEnabled: Boolean,
    language: String,
    onAddExpense: () -> Unit,
    onEditExpense: (ExpenseWithDetails) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenReports: () -> Unit,
    todayEffect: TodayEffect = TodayEffect.NONE,
    todayEffectStyle: TodayEffectStyle = TodayEffectStyle.RING,
    todayEffectPrimaryColor: Color = Color(0xFFFF6D00),
    todayEffectSecondaryColor: Color? = null,
    todayEffectSpeed: Float = 1f
) {
    val languageManager = LocalLanguageManager.current
    val context = LocalContext.current
    var showFilterSheet by remember { mutableStateOf(false) }
    // Scan needs Vision installed to even launch, and Commander installed for the OCR-cleanup step
    // that runs after — stays visible but dimmed, with an explanatory toast on tap naming whichever
    // one is actually missing, rather than silently failing (or crashing, for the Vision case) if
    // either isn't installed.
    val visionInstalled = remember { VoxAppsDiscovery.isAppInstalled(context, VoxIpc.VISION_PACKAGE) }
    val commanderInstalled = remember { VoxAppsDiscovery.isCommanderInstalled(context) }
    val scanGate = rememberRequirementGate(
        satisfied = visionInstalled && commanderInstalled,
        requiredMessage = languageManager.getString(
            if (!visionInstalled) "vision_required_message" else "commander_required_message"
        )
    ) { ExpenseScanRequestSender.send(context, returnToCaller = true) }

    // Amount-sorted order isn't chronological, so it doesn't fit a per-day calendar layout — a
    // derived rule, no extra persisted state: clearing the sort (the chip's X) automatically
    // restores the calendar view if the underlying setting is on.
    val effectiveViewIsCalendar = calendarViewEnabled && !state.isAmountSort

    DoubleBackToExitHandler(message = languageManager.getString("press_back_again_to_exit"))

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(languageManager.getString("expenses_title")) },
                actions = {
                    IconButton(onClick = { showFilterSheet = true }) {
                        Icon(Icons.Filled.CalendarMonth, contentDescription = languageManager.getString("sort_and_filter"))
                    }
                    IconButton(onClick = onOpenReports) {
                        Icon(Icons.Filled.Assessment, contentDescription = languageManager.getString("reports_title"))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = languageManager.getString("settings"))
                    }
                }
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                ExtendedFloatingActionButton(
                    onClick = scanGate.onClick,
                    icon = { Icon(Icons.Filled.DocumentScanner, contentDescription = null) },
                    text = { Text(languageManager.getString("scan_receipt")) },
                    modifier = Modifier.padding(bottom = 12.dp).alpha(scanGate.alpha)
                )
                FloatingActionButton(onClick = onAddExpense) {
                    Icon(Icons.Filled.Add, contentDescription = languageManager.getString("add_expense"))
                }
            }
        }
    ) { padding ->
        val dayDots = remember(state.expenses) {
            state.expenses.groupBy {
                com.voxapps.calendar.CalendarDateUtils.millisToLocalDate(it.expense.dateTime)
            }.mapValues { (_, expenses) ->
                expenses.mapNotNull { it.category?.colorArgb }.distinct()
            }
        }
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.categories.isNotEmpty() || state.isAmountSort) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (state.categories.isNotEmpty()) {
                        FilterChip(
                            selected = state.selectedCategoryId == null,
                            onClick = { stateManager.setCategoryFilter(null) },
                            label = { Text(languageManager.getString("all_expenses")) }
                        )
                    }
                    if (state.isAmountSort) {
                        InputChip(
                            selected = true,
                            onClick = {},
                            label = {
                                Text(
                                    languageManager.getString(
                                        if (state.sort == SortMode.AMOUNT_DESC) "sorted_by_amount_desc" else "sorted_by_amount_asc"
                                    )
                                )
                            },
                            trailingIcon = {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = languageManager.getString("clear"),
                                    modifier = Modifier.clickable { stateManager.setSort(SortMode.NEWEST) }
                                )
                            }
                        )
                    }
                }
            }

            if (state.expenses.isEmpty()) {
                Column(modifier = Modifier.fillMaxSize().padding(32.dp)) {
                    Text(
                        languageManager.getString("no_expenses_yet"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (effectiveViewIsCalendar) {
                CalendarView(
                    items = state.expenses.map(::ExpenseCalendarItem),
                    modifier = Modifier.fillMaxSize(),
                    locale = java.util.Locale.forLanguageTag(language),
                    todayContentDescription = languageManager.getString("today"),
                    selectedDateMillis = state.selectedDateMillis,
                    isGridView = state.isGridView,
                    onToggleGridView = { stateManager.setIsGridView(!state.isGridView) },
                    onDateSelected = { stateManager.setSelectedDate(it) },
                    dayDots = dayDots,
                    todayEffect = todayEffect,
                    todayEffectStyle = todayEffectStyle,
                    todayEffectPrimaryColor = todayEffectPrimaryColor,
                    todayEffectSecondaryColor = todayEffectSecondaryColor,
                    todayEffectSpeed = todayEffectSpeed,
                    itemContent = { calItem ->
                        ExpenseCard(expenseWithDetails = calItem.ewd, onClick = { onEditExpense(calItem.ewd) })
                    }
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.expenses, key = { it.expense.id }) { ewd ->
                        ExpenseCard(expenseWithDetails = ewd, onClick = { onEditExpense(ewd) })
                    }
                }
            }
        }
    }

    if (showFilterSheet) {
        ExpenseFilterSortSheet(
            sort = state.sort,
            dateFrom = state.dateFrom,
            dateTo = state.dateTo,
            selectedBank = state.selectedBank,
            selectedVendor = state.selectedVendor,
            availableBanks = state.availableBanks,
            availableVendors = state.availableVendors,
            onApply = { sort, from, to, bank, vendor ->
                stateManager.setSort(sort)
                stateManager.setDateFilter(from, to)
                stateManager.setBankFilter(bank)
                stateManager.setVendorFilter(vendor)
                showFilterSheet = false
            },
            onClear = {
                stateManager.clearDateFilter()
                stateManager.setBankFilter(null)
                stateManager.setVendorFilter(null)
                showFilterSheet = false
            },
            onDismiss = { showFilterSheet = false }
        )
    }
}
