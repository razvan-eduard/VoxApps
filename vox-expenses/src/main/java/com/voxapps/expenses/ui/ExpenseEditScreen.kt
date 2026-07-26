package com.voxapps.expenses.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast
import com.voxapps.attachments.AttachmentFileStore
import com.voxapps.attachments.ui.AttachmentUiItem
import com.voxapps.attachments.ui.AttachmentsSection
import com.voxapps.attachments.ui.rememberCameraCaptureLauncher
import com.voxapps.design.color.VoxColorSwatchPicker
import com.voxapps.expenses.ExpensesApplication
import com.voxapps.expenses.data.ExpensesAttachments
import com.voxapps.expenses.data.Category
import com.voxapps.expenses.data.CategoryPalette
import com.voxapps.expenses.data.DUPLICATE_ENTRY_RESULT
import com.voxapps.expenses.data.Expense
import com.voxapps.expenses.data.ExpenseLineItem
import com.voxapps.expenses.data.ExpenseSanitizer
import com.voxapps.expenses.data.ExpenseWithDetails
import com.voxapps.expenses.data.NEAR_DUPLICATE_MERGED_RESULT
import com.voxapps.expenses.data.TransactionDirection
import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.expenses.domain.llm.ExpenseAmountMismatch
import com.voxapps.expenses.domain.llm.ExpenseScanCleanupRequestSender
import com.voxapps.expenses.domain.llm.MultimodalAttachmentResolver
import com.voxapps.expenses.domain.location.ExpensesLocationHelper
import com.voxapps.expenses.domain.localization.LanguageManager
import com.voxapps.expenses.state.ExpensesStateManager
import com.voxapps.datahygiene.DirtyField
import com.voxapps.datahygiene.RecordSource
import com.voxapps.datahygiene.SaveDecision
import com.voxapps.datahygiene.decideForSave
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Date

private val ConfirmGreen = Color(0xFF4CAF50)
private val OffenseRed = Color(0xFFD32F2F)

private data class LineItemDraft(
    var name: String,
    var quantityText: String,
    var unitPriceText: String,
    var netAmountText: String = "",
    var vatAmountText: String = "",
    var grossAmountText: String = ""
)

private fun LineItemDraft.subtotal(useComma: Boolean): Double =
    (parseDecimalOrNull(quantityText, useComma) ?: 0.0) * (parseDecimalOrNull(unitPriceText, useComma) ?: 0.0)

private data class PendingCleanup(val expense: Expense, val items: List<ExpenseLineItem>, val dirtyFields: List<DirtyField>)

/** Deletes every staged-but-unlinked attachment file for a new expense that ends up never actually
 *  becoming a real row (screen closed without saving, or the save turned out to be a duplicate/merge
 *  rather than a new insert) — the counterpart to linking the same list once a real id exists. */
private fun discardPendingAttachments(fileNames: List<String>, context: Context) {
    fileNames.forEach { fileName -> AttachmentFileStore.delete(context, ExpensesAttachments.DIR, fileName) }
}

private fun expenseFieldLabel(languageManager: LanguageManager, fieldKey: String): String = when (fieldKey) {
    "title" -> languageManager.getString("expense_title_optional")
    "vendor" -> languageManager.getString("expense_vendor")
    "bank" -> languageManager.getString("expense_bank")
    "location" -> languageManager.getString("expense_location")
    "comments" -> languageManager.getString("expense_comments")
    else -> fieldKey
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseEditScreen(
    existing: ExpenseWithDetails?,
    categories: List<Category>,
    defaultCurrency: String,
    vatDisplayEnabled: Boolean,
    decimalSeparator: String,
    locationPrefillEnabled: Boolean,
    mostRecentCategoryColor: Long? = null,
    stateManager: ExpensesStateManager,
    onDone: () -> Unit
) {
    val languageManager = LocalLanguageManager.current
    val useComma = decimalSeparator == ExpensesSettings.DECIMAL_COMMA
    val context = LocalContext.current

    var title by remember { mutableStateOf(existing?.expense?.title ?: "") }
    var totalText by remember { mutableStateOf(existing?.expense?.totalAmount?.let { formatDecimal(it, useComma) } ?: "") }
    var currency by remember { mutableStateOf(existing?.expense?.currencyCode ?: defaultCurrency) }
    var vendor by remember { mutableStateOf(existing?.expense?.vendor ?: "") }
    var bank by remember { mutableStateOf(existing?.expense?.bank ?: "") }
    var location by remember { mutableStateOf(existing?.expense?.location ?: "") }
    // New expense only (never overrides a real edit) — resolveCurrentCity's own first step is a
    // synchronous cache check, so this resolves near-instantly when a fresh city is already cached,
    // and only falls through to an actual GPS/network round-trip otherwise. Checks location.isBlank()
    // right before writing so a user who typed their own location first (however unlikely, given
    // this runs immediately on open) never gets overwritten.
    if (existing == null && locationPrefillEnabled) {
        LaunchedEffect(Unit) {
            val city = ExpensesLocationHelper.resolveCurrentCity(context)
            if (city != null && location.isBlank()) location = city
        }
    }
    var comments by remember { mutableStateOf(existing?.expense?.comments ?: "") }
    var dateTime by remember { mutableStateOf(existing?.expense?.dateTime ?: System.currentTimeMillis()) }
    var categoryId by remember { mutableStateOf(existing?.expense?.categoryId) }
    var categoryMenuExpanded by remember { mutableStateOf(false) }
    var showNewCategoryDialog by remember { mutableStateOf(false) }
    var direction by remember { mutableStateOf(existing?.expense?.direction ?: TransactionDirection.OUTGOING) }
    var directionMenuExpanded by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showDeleteExpenseConfirm by remember { mutableStateOf(false) }
    var pendingDeleteItemIndex by remember { mutableStateOf<Int?>(null) }
    var pendingCleanup by remember { mutableStateOf<PendingCleanup?>(null) }
    // Photos staged (via AttachmentFileStore, no id needed for that) while composing a brand-new
    // expense that doesn't have a real id yet — linked to the real expense id once saveExpense
    // actually creates the row (see its onResult below), or deleted if the screen closes without
    // ever saving. Always empty when editing an existing expense (attachments then read/write
    // straight to the database via ExpenseAttachmentsSection instead).
    var pendingAttachments by remember { mutableStateOf<List<String>>(emptyList()) }

    fun saveExpense(expense: Expense, lineItems: List<ExpenseLineItem>) {
        // A genuine manual category change — covers both "edited an existing expense's category"
        // and "picked a category on a brand-new manual entry" (the pre-edit value is null for a new
        // expense, so picking anything counts as a change). Never fires from voice/scan/notification
        // capture, since those paths never route through this screen at creation time.
        if (expense.categoryId != existing?.expense?.categoryId) {
            stateManager.recordManualCategoryChange(expense.vendor, expense.categoryId)
        }
        if (existing != null) {
            stateManager.updateExpense(expense, lineItems)
        } else {
            stateManager.addExpense(
                title = expense.title,
                totalAmount = expense.totalAmount,
                currencyCode = expense.currencyCode,
                vendor = expense.vendor,
                bank = expense.bank,
                location = expense.location,
                dateTime = expense.dateTime,
                comments = expense.comments,
                categoryId = expense.categoryId,
                items = lineItems,
                direction = expense.direction,
                context = context,
                onResult = { id ->
                    if (id == DUPLICATE_ENTRY_RESULT) {
                        Toast.makeText(context, languageManager.getString("duplicate_entry_error"), Toast.LENGTH_LONG).show()
                        discardPendingAttachments(pendingAttachments, context)
                    } else if (id == NEAR_DUPLICATE_MERGED_RESULT) {
                        Toast.makeText(context, languageManager.getString("near_duplicate_merged_message"), Toast.LENGTH_LONG).show()
                        discardPendingAttachments(pendingAttachments, context)
                    } else if (id > 0) {
                        // A genuinely new row — link the already-staged files (see pendingAttachments
                        // above) to its real id now that one exists.
                        pendingAttachments.forEach { fileName -> stateManager.addManualAttachment(id, fileName) }
                    }
                    pendingAttachments = emptyList()
                }
            )
        }
    }

    val items = remember {
        mutableStateListOf<LineItemDraft>().apply {
            existing?.items?.forEach {
                add(
                    LineItemDraft(
                        name = it.name,
                        quantityText = formatDecimal(it.quantity, useComma),
                        unitPriceText = formatDecimal(it.unitPrice, useComma),
                        netAmountText = it.netAmount?.let { v -> formatDecimal(v, useComma) } ?: "",
                        vatAmountText = it.vatAmount?.let { v -> formatDecimal(v, useComma) } ?: "",
                        grossAmountText = it.grossAmount?.let { v -> formatDecimal(v, useComma) } ?: ""
                    )
                )
            }
        }
    }

    val itemsSum = items.sumOf { it.subtotal(useComma) }
    val totalMismatch = items.isNotEmpty() &&
        (parseDecimalOrNull(totalText, useComma)?.let { ExpenseAmountMismatch.isMismatch(it, itemsSum) } ?: false)

    // Shared by the checkmark button, the back arrow, and the system back gesture/button — this
    // screen has no separate "discard changes" path, so leaving it any way always tries to save
    // first. No valid amount (the one mandatory field) has nothing meaningful to save, so that case
    // just closes without writing anything, matching the old Save button's
    // `enabled = parseDecimalOrNull(totalText, useComma) != null` guard.
    fun attemptSaveAndClose() {
        val total = parseDecimalOrNull(totalText, useComma)
        if (total == null) {
            // Nothing will ever link these now — a no-op when editing an existing expense, since
            // pendingAttachments is only ever populated for a new, not-yet-saved one.
            discardPendingAttachments(pendingAttachments, context)
            onDone()
            return
        }
        val lineItems = items
            .filter { it.name.isNotBlank() }
            .map {
                ExpenseLineItem(
                    expenseId = existing?.expense?.id ?: 0,
                    name = it.name.trim(),
                    quantity = parseDecimalOrNull(it.quantityText, useComma) ?: 1.0,
                    unitPrice = parseDecimalOrNull(it.unitPriceText, useComma) ?: 0.0,
                    netAmount = parseDecimalOrNull(it.netAmountText, useComma),
                    vatAmount = parseDecimalOrNull(it.vatAmountText, useComma),
                    grossAmount = parseDecimalOrNull(it.grossAmountText, useComma)
                )
            }
        val candidate = (existing?.expense ?: Expense(totalAmount = total, currencyCode = currency, dateTime = dateTime)).copy(
            title = title,
            totalAmount = total,
            currencyCode = currency.ifBlank { defaultCurrency },
            vendor = vendor,
            bank = bank,
            location = location,
            dateTime = dateTime,
            comments = comments,
            categoryId = categoryId,
            direction = direction,
            isStub = false
        )
        when (val decision = ExpenseSanitizer.decideForSave(candidate, RecordSource.MANUAL_UI)) {
            is SaveDecision.Proceed -> {
                saveExpense(decision.record, lineItems)
                onDone()
            }
            is SaveDecision.ConfirmCleanup -> {
                pendingCleanup = PendingCleanup(decision.original, lineItems, decision.dirtyFields)
            }
        }
    }

    BackHandler { attemptSaveAndClose() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(languageManager.getString(if (existing != null) "edit_expense" else "new_expense")) },
                navigationIcon = {
                    IconButton(onClick = ::attemptSaveAndClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = languageManager.getString("back"))
                    }
                },
                actions = {
                    if (existing != null) {
                        IconButton(onClick = { showDeleteExpenseConfirm = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = languageManager.getString("delete"))
                        }
                    }
                    IconButton(onClick = ::attemptSaveAndClose, enabled = parseDecimalOrNull(totalText, useComma) != null) {
                        Icon(Icons.Filled.Check, contentDescription = languageManager.getString("save"))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val imageName = existing?.expense?.receiptImageName
            if (existing?.expense?.isStub == true) {
                item {
                    StubRetryBanner(expenseId = existing.expense.id, imageName = imageName, stateManager = stateManager, onDone = onDone)
                }
            }
            if (existing?.expense?.id != null) {
                item {
                    ExpenseAttachmentsSection(existing.expense.id, imageName, stateManager)
                }
            } else {
                item {
                    PendingExpenseAttachmentsSection(
                        pendingAttachments = pendingAttachments,
                        onChange = { pendingAttachments = it }
                    )
                }
            }

            item {
                SectionCard {
                    SectionTitle(languageManager.getString("expense_details"))

                    PaperField(
                        label = languageManager.getString("expense_title_optional"),
                        value = title,
                        onValueChange = { title = it }
                    )
                    PaperField(
                        label = languageManager.getString("expense_vendor"),
                        value = vendor,
                        onValueChange = { vendor = it }
                    )
                    PaperField(
                        label = languageManager.getString("expense_bank"),
                        value = bank,
                        onValueChange = { bank = it }
                    )
                    PaperField(
                        label = languageManager.getString("expense_location"),
                        value = location,
                        onValueChange = { location = it }
                    )
                    PaperTapField(
                        label = languageManager.getString("expense_date"),
                        value = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(dateTime)),
                        onClick = { showDatePicker = true },
                        trailingIcon = {
                            Icon(
                                Icons.Filled.CalendarMonth,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    )
                    PaperTapField(
                        label = languageManager.getString("expense_time"),
                        value = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(dateTime)),
                        onClick = { showTimePicker = true },
                        trailingIcon = {
                            Icon(
                                Icons.Filled.AccessTime,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    )
                    Box {
                        PaperTapField(
                            label = languageManager.getString("expense_category"),
                            value = categories.firstOrNull { it.id == categoryId }?.name ?: languageManager.getString("none"),
                            onClick = { categoryMenuExpanded = true },
                            trailingIcon = {
                                Icon(
                                    Icons.Filled.ExpandMore,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        )
                        DropdownMenu(expanded = categoryMenuExpanded, onDismissRequest = { categoryMenuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text(languageManager.getString("none")) },
                                onClick = { categoryId = null; categoryMenuExpanded = false }
                            )
                            DropdownMenuItem(
                                text = { Text(languageManager.getString("new_category_dropdown_item")) },
                                onClick = { categoryMenuExpanded = false; showNewCategoryDialog = true }
                            )
                            categories.forEach { cat ->
                                DropdownMenuItem(
                                    text = { Text(cat.name) },
                                    leadingIcon = {
                                        Box(
                                            modifier = Modifier
                                                .size(14.dp)
                                                .clip(CircleShape)
                                                .background(CategoryColors.fromStored(cat.colorArgb))
                                        )
                                    },
                                    onClick = { categoryId = cat.id; categoryMenuExpanded = false }
                                )
                            }
                        }
                    }
                    PaperField(
                        label = languageManager.getString("expense_comments"),
                        value = comments,
                        onValueChange = { comments = it },
                        singleLine = false,
                        minLines = 2
                    )

                    val amountColor = if (totalMismatch) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                        PaperField(
                            label = languageManager.getString("expense_amount"),
                            value = totalText,
                            onValueChange = { totalText = it },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            valueColor = amountColor,
                            dividerColor = amountColor.copy(alpha = if (totalMismatch) 1f else 0.4f),
                            modifier = Modifier.weight(1f)
                        )
                        PaperField(
                            label = languageManager.getString("expense_currency"),
                            value = currency,
                            onValueChange = { currency = it.uppercase().take(3) },
                            modifier = Modifier.weight(0.6f)
                        )
                    }
                    if (totalMismatch) {
                        Text(
                            languageManager.getString("expense_total_mismatch"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Box {
                        PaperTapField(
                            label = languageManager.getString("expense_direction"),
                            value = languageManager.getString(
                                if (direction == TransactionDirection.OUTGOING) "transaction_direction_outgoing" else "transaction_direction_incoming"
                            ),
                            onClick = { directionMenuExpanded = true },
                            trailingIcon = {
                                Icon(
                                    Icons.Filled.ExpandMore,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        )
                        DropdownMenu(expanded = directionMenuExpanded, onDismissRequest = { directionMenuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text(languageManager.getString("transaction_direction_outgoing")) },
                                onClick = { direction = TransactionDirection.OUTGOING; directionMenuExpanded = false }
                            )
                            DropdownMenuItem(
                                text = { Text(languageManager.getString("transaction_direction_incoming")) },
                                onClick = { direction = TransactionDirection.INCOMING; directionMenuExpanded = false }
                            )
                        }
                    }
                }
            }

            item {
                SectionCard {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        SectionTitle(languageManager.getString("line_items"))
                        TextButton(onClick = {
                            items.add(LineItemDraft("", "1", ""))
                        }) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Text(languageManager.getString("add_item"))
                        }
                    }

                    if (items.isNotEmpty()) {
                        LineItemHeaderRow(vatDisplayEnabled, languageManager)
                    }

                    items.forEachIndexed { index, draftItem ->
                        LineItemCard(
                            item = draftItem,
                            vatDisplayEnabled = vatDisplayEnabled,
                            languageManager = languageManager,
                            onCommit = { items[index] = it },
                            onDelete = { pendingDeleteItemIndex = index }
                        )
                    }
                }
            }

        }
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = dateTime)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { selectedDateMillis ->
                        val selectedDate = Instant.ofEpochMilli(selectedDateMillis).atZone(ZoneId.systemDefault()).toLocalDate()
                        val currentTime = Instant.ofEpochMilli(dateTime).atZone(ZoneId.systemDefault()).toLocalTime()
                        dateTime = LocalDateTime.of(selectedDate, currentTime).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    }
                    showDatePicker = false
                }) { Text(languageManager.getString("apply")) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text(languageManager.getString("cancel")) }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showTimePicker) {
        val initialTime = Instant.ofEpochMilli(dateTime).atZone(ZoneId.systemDefault()).toLocalTime()
        val timePickerState = rememberTimePickerState(
            initialHour = initialTime.hour,
            initialMinute = initialTime.minute,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val currentDate = Instant.ofEpochMilli(dateTime).atZone(ZoneId.systemDefault()).toLocalDate()
                    val selectedTime = LocalTime.of(timePickerState.hour, timePickerState.minute)
                    dateTime = LocalDateTime.of(currentDate, selectedTime).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    showTimePicker = false
                }) { Text(languageManager.getString("apply")) }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text(languageManager.getString("cancel")) }
            },
            text = {
                TimePicker(state = timePickerState)
            }
        )
    }

    if (showDeleteExpenseConfirm && existing != null) {
        ConfirmDeleteDialog(
            title = languageManager.getString("delete_expense_title"),
            message = languageManager.getString("delete_expense_message"),
            onConfirm = {
                stateManager.deleteExpense(existing.expense)
                showDeleteExpenseConfirm = false
                onDone()
            },
            onDismiss = { showDeleteExpenseConfirm = false }
        )
    }

    pendingCleanup?.let { pending ->
        ConfirmCleanupDialog(
            dirtyFields = pending.dirtyFields,
            onConfirm = {
                saveExpense(ExpenseSanitizer.sanitize(pending.expense), pending.items)
                pendingCleanup = null
                onDone()
            },
            onDismiss = { pendingCleanup = null }
        )
    }

    pendingDeleteItemIndex?.let { index ->
        ConfirmDeleteDialog(
            title = languageManager.getString("delete_item_title"),
            message = languageManager.getString("delete_item_message"),
            onConfirm = {
                items.removeAt(index)
                pendingDeleteItemIndex = null
            },
            onDismiss = { pendingDeleteItemIndex = null }
        )
    }

    if (showNewCategoryDialog) {
        NewCategoryDialog(
            existingColors = categories.map { it.colorArgb },
            precedingColor = mostRecentCategoryColor,
            onDismiss = { showNewCategoryDialog = false },
            onConfirm = { name, color ->
                stateManager.addCategory(name, color, onResult = { newId -> if (newId > 0) categoryId = newId })
                showNewCategoryDialog = false
            }
        )
    }
}

@Composable
private fun NewCategoryDialog(
    existingColors: List<Long>,
    precedingColor: Long?,
    onDismiss: () -> Unit,
    onConfirm: (name: String, colorArgb: Long) -> Unit
) {
    val languageManager = LocalLanguageManager.current
    var name by remember { mutableStateOf("") }
    var selectedColor by remember(existingColors, precedingColor) {
        mutableStateOf(CategoryPalette.unusedOrRandomColor(existingColors, precedingColor))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(languageManager.getString("new_category_dialog_title")) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(languageManager.getString("category_name")) },
                    singleLine = true
                )
                VoxColorSwatchPicker(
                    selectedColor = selectedColor,
                    onColorSelected = { selectedColor = it },
                    modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
                    customColorDialogTitle = languageManager.getString("custom_color_title"),
                    customColorUseLabel = languageManager.getString("use_color_button"),
                    customColorCancelLabel = languageManager.getString("cancel"),
                    customColorHueLabel = languageManager.getString("hue_label"),
                    customColorSaturationLabel = languageManager.getString("saturation_label"),
                    customColorBrightnessLabel = languageManager.getString("brightness_label")
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name.trim(), selectedColor) }, enabled = name.isNotBlank()) {
                Text(languageManager.getString("save"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(languageManager.getString("cancel")) }
        }
    )
}

@Composable
private fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

/** One photo this expense could send to the LLM on retry: the original scan, or a manually-added
 *  attachment (see :core:attachments). [dirName]/[fileName] locate the actual file for
 *  [MultimodalAttachmentResolver]; [isOriginalScan] picks which resolver function/downscaling
 *  convention applies. */
private data class RetryPhotoCandidate(
    val label: String,
    val dirName: String,
    val fileName: String,
    val isOriginalScan: Boolean
)

@Composable
private fun StubRetryBanner(expenseId: Long, imageName: String?, stateManager: ExpensesStateManager, onDone: () -> Unit) {
    val context = LocalContext.current
    val languageManager = LocalLanguageManager.current
    val scope = rememberCoroutineScope()
    var retrying by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    val manualAttachments by stateManager.observeAttachments(expenseId).collectAsStateWithLifecycle(initialValue = emptyList())

    fun sendRetry(candidate: RetryPhotoCandidate) {
        val rawTextFile = imageName?.let {
            File(File(context.filesDir, "receipts"), it.substringBeforeLast('.') + ".txt")
        }
        val rawText = rawTextFile?.takeIf { it.exists() }?.readText()
        if (imageName == null || rawText.isNullOrBlank()) {
            Toast.makeText(context, languageManager.getString("retry_cleanup_no_saved_text"), Toast.LENGTH_LONG).show()
            return
        }
        retrying = true
        val container = (context.applicationContext as ExpensesApplication).container
        scope.launch {
            // Same staged AI copy OcrResultReceiver already prepared for the original scan (if
            // Vision's own setting produced one) — gated on its own attachPhotoOnRetry toggle,
            // separate from attachPhotoOnScan (see ExpensesSettings' doc comments for why retry is
            // treated as a distinct decision). Applies to a manually-picked candidate too, so the
            // toggle stays a single "attach a photo on retry, yes/no" decision either way.
            val attachOnRetry = container.settingsRepository.getSnapshot().attachPhotoOnRetry
            val attachmentUri = if (candidate.isOriginalScan) {
                MultimodalAttachmentResolver.resolve(context, imageName, attachOnRetry)
            } else {
                MultimodalAttachmentResolver.resolveArbitraryFile(context, candidate.dirName, candidate.fileName, attachOnRetry)
            }
            ExpenseScanCleanupRequestSender.send(
                context, container, rawText, imageName, retryOfExpenseId = expenseId, attachmentUri = attachmentUri
            )
        }
        Toast.makeText(context, languageManager.getString("retrying_scan"), Toast.LENGTH_SHORT).show()
        onDone()
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    languageManager.getString("manual_review_required"),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Button(
                enabled = !retrying,
                onClick = {
                    // Only the original scan exists as a candidate unless manual attachments have
                    // also been added — in that common case, behave exactly as before (no picker).
                    if (manualAttachments.isEmpty() || imageName == null) {
                        sendRetry(RetryPhotoCandidate(languageManager.getString("retry_original_scan"), "receipts", imageName.orEmpty(), isOriginalScan = true))
                    } else {
                        showPicker = true
                    }
                }
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(languageManager.getString("retry_cleanup"))
            }
        }
    }

    if (showPicker && imageName != null) {
        val candidates = remember(manualAttachments) {
            listOf(RetryPhotoCandidate(languageManager.getString("retry_original_scan"), "receipts", imageName, isOriginalScan = true)) +
                manualAttachments.mapIndexed { index, entity ->
                    RetryPhotoCandidate(
                        String.format(languageManager.getString("retry_attachment_n"), index + 1),
                        ExpensesAttachments.DIR,
                        entity.fileName,
                        isOriginalScan = false
                    )
                }
        }
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text(languageManager.getString("retry_pick_photo")) },
            text = {
                Column {
                    candidates.forEach { candidate ->
                        TextButton(
                            onClick = {
                                showPicker = false
                                sendRetry(candidate)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(candidate.label, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text(languageManager.getString("cancel")) }
            }
        )
    }
}

/** Unifies the original receipt scan (if any — first, non-removable) with manually-added
 *  attachments (see :core:attachments) into one section, per the shared AttachmentsSection UI. */
@Composable
private fun ExpenseAttachmentsSection(expenseId: Long, receiptImageName: String?, stateManager: ExpensesStateManager) {
    val languageManager = LocalLanguageManager.current
    val context = LocalContext.current
    val manualEntities by stateManager.observeAttachments(expenseId).collectAsStateWithLifecycle(initialValue = emptyList())
    val items = remember(receiptImageName, manualEntities) {
        buildList {
            if (!receiptImageName.isNullOrBlank()) {
                add(
                    AttachmentUiItem(
                        id = -1L,
                        uri = AttachmentFileStore.uriFor(context, ExpensesAttachments.FILE_PROVIDER_AUTHORITY, "receipts", receiptImageName),
                        removable = false
                    )
                )
            }
            addAll(
                manualEntities.map { e ->
                    AttachmentUiItem(
                        id = e.id,
                        uri = AttachmentFileStore.uriFor(context, ExpensesAttachments.FILE_PROVIDER_AUTHORITY, ExpensesAttachments.DIR, e.fileName),
                        removable = true
                    )
                }
            )
        }
    }
    fun handlePickedUri(uri: Uri?) {
        if (uri != null) {
            AttachmentFileStore.stage(context, uri, ExpensesAttachments.DIR)?.let { fileName ->
                stateManager.addManualAttachment(expenseId, fileName)
            }
        }
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> handlePickedUri(uri) }
    val takePhoto = rememberCameraCaptureLauncher(ExpensesAttachments.FILE_PROVIDER_AUTHORITY) { uri -> handlePickedUri(uri) }
    AttachmentsSection(
        title = languageManager.getString("attachments"),
        items = items,
        canAdd = manualEntities.size < 10,
        onPickFromGallery = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onTakePhoto = takePhoto,
        galleryLabel = languageManager.getString("attachment_choose_gallery"),
        cameraLabel = languageManager.getString("attachment_take_photo"),
        cancelLabel = languageManager.getString("cancel"),
        onRemove = { item ->
            manualEntities.firstOrNull { it.id == item.id }?.let { stateManager.removeAttachment(it, context) }
        },
        modifier = Modifier.padding(bottom = 12.dp)
    )
}

/** Attachments UI for a not-yet-saved expense: stages picked photos into this app's files dir via
 *  [AttachmentFileStore] immediately (no expense id needed for that), but only tracks them as local
 *  filenames — no [com.voxapps.attachments.AttachmentEntity] row exists until [ExpenseEditScreen]
 *  links them once the expense is actually saved (or deletes the staged files if the draft is
 *  discarded instead). A fake id derived from the filename's hash is enough for [AttachmentsSection]'s
 *  list key — it never needs a real database id. */
@Composable
private fun PendingExpenseAttachmentsSection(pendingAttachments: List<String>, onChange: (List<String>) -> Unit) {
    val languageManager = LocalLanguageManager.current
    val context = LocalContext.current
    val items = remember(pendingAttachments) {
        pendingAttachments.map { fileName ->
            AttachmentUiItem(
                id = fileName.hashCode().toLong(),
                uri = AttachmentFileStore.uriFor(context, ExpensesAttachments.FILE_PROVIDER_AUTHORITY, ExpensesAttachments.DIR, fileName),
                removable = true
            )
        }
    }
    fun handlePickedUri(uri: Uri?) {
        if (uri != null) {
            AttachmentFileStore.stage(context, uri, ExpensesAttachments.DIR)?.let { fileName ->
                onChange(pendingAttachments + fileName)
            }
        }
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> handlePickedUri(uri) }
    val takePhoto = rememberCameraCaptureLauncher(ExpensesAttachments.FILE_PROVIDER_AUTHORITY) { uri -> handlePickedUri(uri) }
    AttachmentsSection(
        title = languageManager.getString("attachments"),
        items = items,
        canAdd = pendingAttachments.size < 10,
        onPickFromGallery = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onTakePhoto = takePhoto,
        galleryLabel = languageManager.getString("attachment_choose_gallery"),
        cameraLabel = languageManager.getString("attachment_take_photo"),
        cancelLabel = languageManager.getString("cancel"),
        onRemove = { item ->
            pendingAttachments.firstOrNull { it.hashCode().toLong() == item.id }?.let { fileName ->
                AttachmentFileStore.delete(context, ExpensesAttachments.DIR, fileName)
                onChange(pendingAttachments - fileName)
            }
        },
        modifier = Modifier.padding(bottom = 12.dp)
    )
}

@Composable
private fun PaperField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    singleLine: Boolean = true,
    minLines: Int = 1,
    valueColor: Color = MaterialTheme.colorScheme.primary,
    dividerColor: Color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
            if (value.isEmpty()) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = valueColor),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = keyboardOptions,
                singleLine = singleLine,
                minLines = minLines,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = dividerColor, thickness = 1.dp)
    }
}

@Composable
private fun PaperTapField(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailingIcon: @Composable () -> Unit = {}
) {
    Column(modifier = modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
        ) {
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            trailingIcon()
        }
        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), thickness = 1.dp)
    }
}

@Composable
private fun ConfirmDeleteDialog(title: String, message: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val languageManager = LocalLanguageManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(languageManager.getString("delete")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(languageManager.getString("cancel")) }
        }
    )
}

@Composable
private fun ConfirmCleanupDialog(dirtyFields: List<DirtyField>, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val languageManager = LocalLanguageManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(languageManager.getString("cleanup_confirm_title")) },
        text = {
            Column {
                Text(languageManager.getString("cleanup_confirm_message"))
                Spacer(Modifier.height(8.dp))
                dirtyFields.forEach { field ->
                    Text(
                        buildAnnotatedString {
                            append("${expenseFieldLabel(languageManager, field.fieldKey)}: ")
                            withStyle(SpanStyle(color = OffenseRed, fontWeight = FontWeight.Bold)) {
                                append(field.value)
                            }
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(languageManager.getString("auto_clean")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(languageManager.getString("cancel")) }
        }
    )
}

@Composable
private fun LineItemHeaderRow(
    vatDisplayEnabled: Boolean,
    languageManager: com.voxapps.expenses.domain.localization.LanguageManager
) {
    val labelStyle = MaterialTheme.typography.labelMedium
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            Text(languageManager.getString("item_name"), style = labelStyle, color = labelColor, modifier = Modifier.weight(1.4f))
            Text(languageManager.getString("item_qty"), style = labelStyle, color = labelColor, modifier = Modifier.weight(0.7f))
            Text(languageManager.getString("item_unit_price"), style = labelStyle, color = labelColor, modifier = Modifier.weight(0.9f))
            Box(modifier = Modifier.width(40.dp))
        }
        if (vatDisplayEnabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
                Text(languageManager.getString("item_net_amount"), style = labelStyle, color = labelColor, modifier = Modifier.weight(1f))
                Text(languageManager.getString("item_vat_amount"), style = labelStyle, color = labelColor, modifier = Modifier.weight(1f))
                Text(languageManager.getString("item_gross_amount"), style = labelStyle, color = labelColor, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun LineItemCard(
    item: LineItemDraft,
    vatDisplayEnabled: Boolean,
    languageManager: com.voxapps.expenses.domain.localization.LanguageManager,
    onCommit: (LineItemDraft) -> Unit,
    onDelete: () -> Unit
) {
    var isEditing by remember { mutableStateOf(false) }
    var draft by remember(isEditing) { mutableStateOf(item) }

    val cardColors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))

    if (!isEditing) {
        Card(
            onClick = { draft = item; isEditing = true },
            colors = cardColors,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        item.name.ifBlank { "—" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1.4f)
                    )
                    Text(item.quantityText, modifier = Modifier.weight(0.7f))
                    Text(item.unitPriceText, modifier = Modifier.weight(0.9f))
                    IconButton(onClick = onDelete, modifier = Modifier.width(40.dp)) {
                        Icon(Icons.Filled.Delete, contentDescription = languageManager.getString("delete"))
                    }
                }
                if (vatDisplayEnabled) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text(item.netAmountText.ifBlank { "—" }, modifier = Modifier.weight(1f))
                        Text(item.vatAmountText.ifBlank { "—" }, modifier = Modifier.weight(1f))
                        Text(item.grossAmountText.ifBlank { "—" }, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    } else {
        Card(colors = cardColors, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    InlineEditField(
                        value = draft.name,
                        onValueChange = { draft = draft.copy(name = it) },
                        modifier = Modifier.weight(1.4f)
                    )
                    InlineEditField(
                        value = draft.quantityText,
                        onValueChange = { draft = draft.copy(quantityText = it) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(0.7f)
                    )
                    InlineEditField(
                        value = draft.unitPriceText,
                        onValueChange = { draft = draft.copy(unitPriceText = it) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(0.9f)
                    )
                }
                if (vatDisplayEnabled) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        InlineEditField(
                            value = draft.netAmountText,
                            onValueChange = { draft = draft.copy(netAmountText = it) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f)
                        )
                        InlineEditField(
                            value = draft.vatAmountText,
                            onValueChange = { draft = draft.copy(vatAmountText = it) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f)
                        )
                        InlineEditField(
                            value = draft.grossAmountText,
                            onValueChange = { draft = draft.copy(grossAmountText = it) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                ) {
                    IconButton(onClick = { isEditing = false }) {
                        Icon(Icons.Filled.Close, contentDescription = languageManager.getString("cancel"), tint = MaterialTheme.colorScheme.error)
                    }
                    IconButton(onClick = { onCommit(draft); isEditing = false }) {
                        Icon(Icons.Filled.Check, contentDescription = languageManager.getString("save"), tint = ConfirmGreen)
                    }
                }
            }
        }
    }
}

@Composable
private fun InlineEditField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default
) {
    Column(modifier = modifier) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.primary),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = keyboardOptions,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(2.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), thickness = 1.dp)
    }
}
