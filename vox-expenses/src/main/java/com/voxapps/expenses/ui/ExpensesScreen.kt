package com.voxapps.expenses.ui

import java.util.Date
import java.text.DateFormat
import com.voxapps.expenses.state.sortKeyOf
import com.voxapps.expenses.state.ExpenseFilterSummary
import com.voxapps.design.filter.VoxFilterSummary
import com.voxapps.design.filter.VoxFilterButton
import android.widget.Toast
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
import androidx.compose.material.icons.filled.BurstMode
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxapps.attachments.ui.rememberVisionCaptureLauncher
import com.voxapps.calendar.CalendarView
import com.voxapps.design.DoubleBackToExitHandler
import com.voxapps.design.SpeedDialAction
import com.voxapps.design.SpeedDialFab
import com.voxapps.design.effects.TodayEffect
import com.voxapps.design.effects.TodayEffectStyle
import com.voxapps.expenses.ExpensesApplication
import com.voxapps.expenses.data.ExpenseWithDetails
import com.voxapps.design.settings.VoxPendingStrip
import com.voxapps.expenses.domain.health.ExpenseGap
import com.voxapps.expenses.domain.health.ExpenseGaps
import com.voxapps.expenses.ui.settings.SettingsPage
import com.voxapps.expenses.data.RecurringPayment
import com.voxapps.expenses.domain.llm.LlmTasks
import com.voxapps.expenses.domain.recurring.PaymentPredictor
import com.voxapps.expenses.state.ExpensesStateManager
import com.voxapps.expenses.state.ExpensesUiState
import com.voxapps.expenses.state.FilterValue
import com.voxapps.expenses.state.SortMode
import com.voxapps.ipc.VoxAppsDiscovery
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxOcrRequest

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
    /** Straight to one settings page — a count of waiting rules wants the rules, not a menu. */
    onOpenSettingsAt: (SettingsPage) -> Unit = { onOpenSettings() },
    onOpenReports: () -> Unit,
    todayEffect: TodayEffect = TodayEffect.NONE,
    todayEffectStyle: TodayEffectStyle = TodayEffectStyle.RING,
    todayEffectPrimaryColor: Color = Color(0xFFFF6D00),
    todayEffectSecondaryColor: Color? = null,
    todayEffectSpeed: Float = 1f
) {
    val languageManager = LocalLanguageManager.current
    val context = LocalContext.current
    val container = (context.applicationContext as ExpensesApplication).container
    var showFilterSheet by remember { mutableStateOf(false) }
    // Scan needs Vision installed to even launch, and Commander installed for the OCR-cleanup step
    // that runs after — stays visible but dimmed, with an explanatory toast on tap naming whichever
    // one is actually missing, rather than silently failing (or crashing, for the Vision case) if
    // either isn't installed.
    val visionInstalled = remember { VoxAppsDiscovery.isAppInstalled(context, VoxIpc.VISION_PACKAGE) }
    val commanderInstalled = remember { VoxAppsDiscovery.isCommanderInstalled(context) }

    val recurring by stateManager.recurringPayments.collectAsStateWithLifecycle(initialValue = emptyList())
    val confirmedVendorKeys = remember(recurring) {
        recurring.filter { it.confirmed }.map { it.vendorKey }.toSet()
    }
    // A filtered list is a question about what happened; a prediction is not an answer to it. Rather
    // than guess which filters a payment that hasn't happened would satisfy, predictions appear only
    // in the unfiltered list — where "what is coming" is a sensible thing to be told.
    val filtered = state.dateFrom != null || state.dateTo != null ||
        state.selectedBank != null || state.selectedVendor != null ||
        state.selectedLocation != null || state.selectedCategoryId != null
    val predictedPayments = remember(recurring, state.expenses, filtered) {
        if (filtered) emptyList() else PaymentPredictor.predict(
            confirmed = recurring,
            nowMillis = System.currentTimeMillis()
        ) { payment ->
            // The arrangement records its own last arrival, so this only ever fires when the ledger
            // knows about a payment the arrangement missed — a restored backup, or a bookkeeping
            // failure that was logged rather than thrown. Predicting a bill already paid is the one
            // mistake worth a second check.
            state.expenses.any {
                it.expense.dateTime > payment.lastSeenAt &&
                    RecurringPayment.vendorKeyOf(it.expense.vendor) == payment.vendorKey
            }
        }
    }

    // Scan: single/stitch/batch (see VoxOcrRequest.captureMode) — one launch, one reply, handled
    // entirely by OcrResultReceiver.handlePendingScanCreate. No Compose-side state needed here at all
    // (previously a correlationId-keyed bus reassembled shots landing one at a time; that trickle
    // doesn't exist anymore now that capture itself isn't per-shot).
    val scanSingle = rememberVisionCaptureLauncher(
        baseTask = "${LlmTasks.EXPENSE_SCAN_CLEANUP}:pending-create", hint = null, produceOCR = true,
        captureMode = VoxOcrRequest.CAPTURE_MODE_SINGLE, tableMode = true
    )
    val scanStitch = rememberVisionCaptureLauncher(
        baseTask = "${LlmTasks.EXPENSE_SCAN_CLEANUP}:pending-create", hint = null, produceOCR = true,
        captureMode = VoxOcrRequest.CAPTURE_MODE_STITCH, tableMode = true
    )
    val scanBatch = rememberVisionCaptureLauncher(
        baseTask = "${LlmTasks.EXPENSE_SCAN_CLEANUP}:pending-create", hint = null, produceOCR = true,
        captureMode = VoxOcrRequest.CAPTURE_MODE_BATCH, tableMode = true
    )
    fun gatedScan(action: () -> Unit) {
        if (visionInstalled && commanderInstalled) {
            action()
        } else {
            Toast.makeText(
                context,
                languageManager.getString(if (!visionInstalled) "vision_required_message" else "commander_required_message"),
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    val scanActions = listOf(
        SpeedDialAction(Icons.Filled.PhotoCamera, languageManager.getString("capture_mode_single")) { gatedScan(scanSingle) },
        SpeedDialAction(Icons.Filled.Layers, languageManager.getString("capture_mode_stitch")) { gatedScan(scanStitch) },
        SpeedDialAction(Icons.Filled.BurstMode, languageManager.getString("capture_mode_batch")) { gatedScan(scanBatch) }
    )

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
            Column(horizontalAlignment = Alignment.End) {
                SpeedDialFab(
                    actions = scanActions,
                    mainIcon = Icons.Filled.DocumentScanner,
                    mainContentDescription = languageManager.getString("scan_receipt"),
                    modifier = Modifier.padding(bottom = 12.dp)
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
            // Above the list, because it is about something that will land in it.
            // Everything waiting for the person, counted in one line. Each of these already
            // existed somewhere; what was missing was one place that says so.
            val pending by stateManager.pendingCaptures.collectAsStateWithLifecycle(initialValue = 0)
            val stagedCaptures by stateManager.pendingNotificationExpenses
                .collectAsStateWithLifecycle(initialValue = emptyList())
            val proposedRules by stateManager.proposedRuleCount.collectAsStateWithLifecycle(initialValue = 0)
            val incomplete = remember(state.expenses, state.categories, state.bankAccounts) {
                ExpenseGaps.needingAttention(
                    state.expenses,
                    state.categories.firstOrNull { it.isDefault }?.id,
                    accountsInUse = state.bankAccounts.isNotEmpty()
                ).size
            }
            var showAttention by rememberSaveable { mutableStateOf(false) }
            var showPending by rememberSaveable { mutableStateOf(false) }

            VoxPendingStrip(
                count = incomplete + stagedCaptures.size + proposedRules + pending,
                text = { n -> languageManager.getString("attention_strip").format(n) },
                onClick = { showAttention = true }
            )
            if (showAttention) {
                AttentionSheet(
                    items = listOf(
                        AttentionItem("attention_incomplete", incomplete) {
                            stateManager.setNeedsAttentionFilter(true)
                        },
                        AttentionItem("attention_staged", stagedCaptures.size) {
                            onOpenSettingsAt(SettingsPage.NOTIFICATION_CAPTURE)
                        },
                        AttentionItem("attention_rules", proposedRules) {
                            onOpenSettingsAt(SettingsPage.CLEANUP_REMAP)
                        },
                        AttentionItem("attention_queued", pending) { showPending = true }
                    ),
                    onDismiss = { showAttention = false }
                )
            }
            if (showPending) {
                val pendingRows by stateManager.pendingCaptureList
                    .collectAsStateWithLifecycle(initialValue = emptyList())
                PendingCapturesSheet(
                    entries = pendingRows,
                    onRetryNow = { stateManager.retryPendingCapturesNow(context) },
                    onForgetGivenUp = { stateManager.forgetGivenUpCaptures() },
                    onForget = { requestId -> stateManager.forgetPendingCapture(requestId) },
                    onDismiss = { showPending = false }
                )
            }
            ExpenseFilterBar(state = state, stateManager = stateManager)

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
                    // Ahead of the payments that happened, because that is where they are in time.
                    items(predictedPayments, key = { "predicted-${it.payment.id}" }) { predicted ->
                        PredictedPaymentCard(
                            predicted = predicted,
                            categoryColorArgb = state.categories
                                .firstOrNull { it.id == predicted.payment.categoryId }?.colorArgb
                        )
                    }
                    items(state.expenses, key = { it.expense.id }) { ewd ->
                        ExpenseCard(
                            expenseWithDetails = ewd,
                            onClick = { onEditExpense(ewd) },
                            recurring = RecurringPayment.vendorKeyOf(ewd.expense.vendor) in confirmedVendorKeys,
                            // Only while the list is narrowed to them: on the ordinary list this
                            // would be a red line on half the rows, which is not information.
                            missing = if (!state.onlyNeedsAttention) null else {
                                ExpenseGaps.of(
                                    ewd,
                                    state.categories.firstOrNull { it.isDefault }?.id,
                                    accountsInUse = state.bankAccounts.isNotEmpty()
                                ).takeIf { it.isNotEmpty() }
                                    ?.joinToString(" · ") { languageManager.getString(gapLabelKey(it)) }
                            }
                        )
                    }
                }
            }
        }
    }

}

/** What each missing thing is called on screen. */
private fun gapLabelKey(gap: ExpenseGap): String = when (gap) {
    ExpenseGap.UNREAD -> "gap_unread"
    ExpenseGap.TOTALS_DISAGREE -> "gap_totals"
    ExpenseGap.NO_AMOUNT -> "gap_amount"
    ExpenseGap.NO_NAME -> "gap_name"
    ExpenseGap.NO_CATEGORY -> "gap_category"
    ExpenseGap.NO_ACCOUNT -> "gap_account"
}
