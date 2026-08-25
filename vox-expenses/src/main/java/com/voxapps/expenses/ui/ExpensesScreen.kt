package com.voxapps.expenses.ui

import com.voxapps.expenses.domain.accounts.BankAccountTree
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
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.BurstMode
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.voxapps.design.VoxConfirmDialog
import com.voxapps.design.SpeedDialAction
import com.voxapps.design.SpeedDialFab
import com.voxapps.design.effects.TodayEffect
import com.voxapps.design.effects.TodayEffectStyle
import com.voxapps.expenses.ExpensesApplication
import com.voxapps.expenses.data.ExpenseWithDetails
import com.voxapps.design.selection.VoxSelectionBackHandler
import com.voxapps.design.selection.VoxSelectionBar
import com.voxapps.design.selection.rememberVoxSelection
import com.voxapps.design.settings.VoxPendingStrip
import com.voxapps.expenses.domain.health.ExpenseGap
import com.voxapps.expenses.domain.health.ExpenseGaps
import com.voxapps.expenses.ui.settings.SettingsPage
import com.voxapps.expenses.data.preferences.AttentionKind
import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.expenses.data.preferences.Dismissals
import com.voxapps.ipc.VoxLlmRequestQueue
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
    onOpenArchive: () -> Unit = {},
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
    // Picking records out of the list to do one thing to all of them. Nothing is persisted: a
    // selection is about what you are doing right now, and one that survived leaving the screen
    // would be a set of records quietly staged for an edit nobody remembers starting.
    val selection = rememberVoxSelection<Long>()
    var showBulkEdit by remember { mutableStateOf(false) }
    var confirmingArchive by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
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

    val onCardClick: (ExpenseWithDetails) -> Unit = { ewd ->
        selection.tap(ewd.expense.id) { onEditExpense(ewd) }
    }
    val onCardLongClick: (ExpenseWithDetails) -> Unit = { ewd -> selection.start(ewd.expense.id) }

    // Leaving the app is what back means only when there is nothing smaller to leave first.
    DoubleBackToExitHandler(
        message = languageManager.getString("press_back_again_to_exit"),
        enabled = !selection.active
    )
    VoxSelectionBackHandler(selection)

    Scaffold(
        topBar = {
            if (selection.active) {
                VoxSelectionBar(
                    count = selection.size,
                    title = { languageManager.counted("selection_mode_count", it) },
                    onClose = { selection.clear() },
                    closeContentDescription = languageManager.getString("cancel")
                ) {
                    // One control for both halves of the same thought. Taking none leaves the mode
                    // with it, because a selection of nothing is not a mode, it is the list again.
                    val listed = state.expenses.map { it.expense.id }
                    val allPicked = listed.isNotEmpty() && selection.ids.containsAll(listed)
                    IconButton(onClick = {
                        if (allPicked) selection.clear() else selection.selectAll(listed)
                    }) {
                        Icon(
                            Icons.Filled.Checklist,
                            contentDescription = languageManager.getString(
                                if (allPicked) "selection_select_none" else "selection_select_all"
                            )
                        )
                    }
                    IconButton(onClick = { showBulkEdit = true }) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = languageManager.getString("selection_edit")
                        )
                    }
                    IconButton(onClick = { confirmingArchive = true }) {
                        Icon(
                            Icons.Filled.Archive,
                            contentDescription = languageManager.getString("selection_archive")
                        )
                    }
                    // Red, and last: the one action here that cannot be taken back.
                    IconButton(onClick = { confirmingDelete = true }) {
                        Icon(
                            Icons.Filled.DeleteForever,
                            contentDescription = languageManager.getString("selection_delete_forever"),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            } else {
                TopAppBar(
                    title = { Text(languageManager.getString("expenses_title")) },
                    actions = {
                        IconButton(onClick = onOpenReports) {
                            Icon(Icons.Filled.Assessment, contentDescription = languageManager.getString("reports_title"))
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = languageManager.getString("settings"))
                        }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = languageManager.getString("more"))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            // Choosing every record without picking one first. The list a filter has
                            // narrowed is usually the whole point — "the twelve that have no card" is
                            // a set somebody wants to act on as a set, and reaching it by holding one
                            // record and then finding a second control is two gestures too many.
                            //
                            // It takes what the filter defines rather than what the calendar layout
                            // happens to be showing, exactly as the bar's own select-all does; the
                            // count in the bar says immediately how many that turned out to be.
                            DropdownMenuItem(
                                text = { Text(languageManager.getString("selection_select_all")) },
                                leadingIcon = { Icon(Icons.Filled.Checklist, contentDescription = null) },
                                enabled = state.expenses.isNotEmpty(),
                                onClick = {
                                    menuOpen = false
                                    selection.selectAll(state.expenses.map { it.expense.id })
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(languageManager.getString("archive_title")) },
                                leadingIcon = { Icon(Icons.Filled.Archive, contentDescription = null) },
                                onClick = { menuOpen = false; onOpenArchive() }
                            )
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            // Nothing to add while choosing what to change: the buttons would sit over the list
            // being chosen from, offering the one action the mode is not about.
            if (!selection.active) Column(horizontalAlignment = Alignment.End) {
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
            val pendingRows by stateManager.pendingCaptureList
                .collectAsStateWithLifecycle(initialValue = emptyList())
            val stagedCaptures by stateManager.pendingNotificationExpenses
                .collectAsStateWithLifecycle(initialValue = emptyList())
            val proposedRules by stateManager.proposedRuleCount.collectAsStateWithLifecycle(initialValue = 0)
            val seen by stateManager.dismissals.collectAsStateWithLifecycle(initialValue = Dismissals())
            val incomplete = remember(state.expenses, state.categories, state.bankAccounts, seen) {
                ExpenseGaps.needingAttention(
                    state.expenses,
                    state.categories.firstOrNull { it.isDefault }?.id,
                    accountsInUse = state.bankAccounts.isNotEmpty()
                ).count { it.expense.createdAt > seen.incompleteBefore }
            }
            // Staged captures carry the moment they were staged as their id.
            val stagedNew = remember(stagedCaptures, seen) { stagedCaptures.count { it.id > seen.stagedBefore } }
            // Still to be tried, and not already seen — a row past its budget waits for nothing.
            val pending = remember(pendingRows, seen) {
                pendingRows.count {
                    it.attemptCount < VoxLlmRequestQueue.DEFAULT_MAX_ATTEMPTS && it.createdAt > seen.queuedBefore
                }
            }
            var showAttention by rememberSaveable { mutableStateOf(false) }
            var showPending by rememberSaveable { mutableStateOf(false) }

            VoxPendingStrip(
                count = incomplete + stagedNew + proposedRules + pending,
                text = { n -> languageManager.getString("attention_strip").format(n) },
                onClick = { showAttention = true },
                onClearAll = { stateManager.dismissAllAttention() },
                clearContentDescription = languageManager.getString("attention_dismiss")
            )
            if (showAttention) {
                AttentionSheet(
                    items = listOf(
                        AttentionItem(
                            labelKey = "attention_incomplete",
                            count = incomplete,
                            onOpen = { stateManager.setNeedsAttentionFilter(true) },
                            onDismiss = { stateManager.dismissAttention(AttentionKind.INCOMPLETE) }
                        ),
                        AttentionItem(
                            labelKey = "attention_staged",
                            count = stagedNew,
                            onOpen = { onOpenSettingsAt(SettingsPage.NOTIFICATION_CAPTURE) },
                            onDismiss = { stateManager.dismissAttention(AttentionKind.STAGED) }
                        ),
                        AttentionItem(
                            labelKey = "attention_rules",
                            count = proposedRules,
                            onOpen = { onOpenSettingsAt(SettingsPage.CLEANUP_REMAP) },
                            onDismiss = { stateManager.dismissAttention(AttentionKind.RULES) }
                        ),
                        AttentionItem(
                            labelKey = "attention_queued",
                            count = pending,
                            onOpen = { showPending = true },
                            onDismiss = { stateManager.dismissAttention(AttentionKind.QUEUED) }
                        )
                    ),
                    onDismiss = { showAttention = false }
                )
            }
            if (showPending) {
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
                        ExpenseCard(
                            expenseWithDetails = calItem.ewd,
                            onClick = { onCardClick(calItem.ewd) },
                            selected = calItem.ewd.expense.id in selection,
                            onLongClick = { onCardLongClick(calItem.ewd) },
                            bankName = BankAccountTree.bankNameFor(
                                calItem.ewd.expense.bankAccountId, state.bankAccounts
                            ),
                            // The day heading above these rows already said which day; the hour
                            // is what tells two of them apart.
                            showDay = false
                        )
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
                            onClick = { onCardClick(ewd) },
                            selected = ewd.expense.id in selection,
                            onLongClick = { onCardLongClick(ewd) },
                            bankName = BankAccountTree.bankNameFor(ewd.expense.bankAccountId, state.bankAccounts),
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

    if (confirmingArchive) {
        VoxConfirmDialog(
            title = languageManager.counted("archive_confirm_title", selection.size),
            message = languageManager.getString("archive_confirm_message"),
            confirmLabel = languageManager.getString("selection_archive"),
            cancelLabel = languageManager.getString("cancel"),
            onConfirm = {
                stateManager.archiveExpenses(selection.ids) { n ->
                    Toast.makeText(
                        context,
                        languageManager.counted("archive_done", n),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                confirmingArchive = false
                selection.clear()
            },
            onDismiss = { confirmingArchive = false }
        )
    }
    if (confirmingDelete) {
        VoxConfirmDialog(
            title = languageManager.counted("delete_forever_confirm_title", selection.size),
            message = languageManager.getString("delete_forever_confirm_message"),
            confirmLabel = languageManager.getString("selection_delete_forever"),
            cancelLabel = languageManager.getString("cancel"),
            destructive = true,
            countdownSeconds = DELETE_COUNTDOWN_SECONDS,
            onConfirm = {
                stateManager.deleteExpenses(selection.ids)
                confirmingDelete = false
                selection.clear()
            },
            onDismiss = { confirmingDelete = false }
        )
    }
    if (showBulkEdit) {
        val settings by container.settingsRepository.settingsFlow
            .collectAsStateWithLifecycle(initialValue = ExpensesSettings())
        BulkEditSheet(
            records = state.expenses.filter { it.expense.id in selection },
            categories = state.categories,
            accounts = state.bankAccounts,
            locations = state.availableLocations,
            stateManager = stateManager,
            settings = settings,
            onApply = { edit ->
                stateManager.applyBulkEdit(selection.ids, edit) { changed ->
                    Toast.makeText(
                        context,
                        languageManager.counted("bulk_edit_done", changed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                showBulkEdit = false
                selection.clear()
            },
            onDismiss = { showBulkEdit = false }
        )
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
