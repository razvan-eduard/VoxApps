package com.voxapps.expenses.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
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
import me.saket.telephoto.zoomable.coil.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import android.widget.Toast
import com.voxapps.expenses.ExpensesApplication
import com.voxapps.expenses.data.Category
import com.voxapps.expenses.data.DUPLICATE_ENTRY_RESULT
import com.voxapps.expenses.data.Expense
import com.voxapps.expenses.data.ExpenseLineItem
import com.voxapps.expenses.data.ExpenseSanitizer
import com.voxapps.expenses.data.ExpenseWithDetails
import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.expenses.domain.llm.ExpenseAmountMismatch
import com.voxapps.expenses.domain.llm.ExpenseScanCleanupRequestSender
import com.voxapps.expenses.domain.llm.MultimodalAttachmentResolver
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
    var comments by remember { mutableStateOf(existing?.expense?.comments ?: "") }
    var dateTime by remember { mutableStateOf(existing?.expense?.dateTime ?: System.currentTimeMillis()) }
    var categoryId by remember { mutableStateOf(existing?.expense?.categoryId) }
    var categoryMenuExpanded by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showDeleteExpenseConfirm by remember { mutableStateOf(false) }
    var pendingDeleteItemIndex by remember { mutableStateOf<Int?>(null) }
    var pendingCleanup by remember { mutableStateOf<PendingCleanup?>(null) }

    fun saveExpense(expense: Expense, lineItems: List<ExpenseLineItem>) {
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
                onResult = { id ->
                    if (id == DUPLICATE_ENTRY_RESULT) {
                        Toast.makeText(context, languageManager.getString("duplicate_entry_error"), Toast.LENGTH_LONG).show()
                    }
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
                    StubRetryBanner(expenseId = existing.expense.id, imageName = imageName, onDone = onDone)
                }
            }
            if (!imageName.isNullOrBlank()) {
                item {
                    ReceiptImage(imageName)
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
                            categories.forEach { cat ->
                                DropdownMenuItem(
                                    text = { Text(cat.name) },
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

@Composable
private fun StubRetryBanner(expenseId: Long, imageName: String?, onDone: () -> Unit) {
    val context = LocalContext.current
    val languageManager = LocalLanguageManager.current
    val scope = rememberCoroutineScope()
    var retrying by remember { mutableStateOf(false) }

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
                    val rawTextFile = imageName?.let {
                        File(File(context.filesDir, "receipts"), it.substringBeforeLast('.') + ".txt")
                    }
                    val rawText = rawTextFile?.takeIf { it.exists() }?.readText()
                    if (imageName == null || rawText.isNullOrBlank()) {
                        Toast.makeText(context, languageManager.getString("retry_cleanup_no_saved_text"), Toast.LENGTH_LONG).show()
                        return@Button
                    }
                    retrying = true
                    val container = (context.applicationContext as ExpensesApplication).container
                    scope.launch {
                        // Same staged AI copy OcrResultReceiver already prepared for the original scan
                        // (if Vision's own setting produced one) — gated on its own attachPhotoOnRetry
                        // toggle, separate from attachPhotoOnScan (see ExpensesSettings' doc comments
                        // for why retry is treated as a distinct decision).
                        val attachOnRetry = container.settingsRepository.getSnapshot().attachPhotoOnRetry
                        val attachmentUri = MultimodalAttachmentResolver.resolve(context, imageName, attachOnRetry)
                        ExpenseScanCleanupRequestSender.send(
                            context, container, rawText, imageName, retryOfExpenseId = expenseId, attachmentUri = attachmentUri
                        )
                    }
                    Toast.makeText(context, languageManager.getString("retrying_scan"), Toast.LENGTH_SHORT).show()
                    onDone()
                }
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(languageManager.getString("retry_cleanup"))
            }
        }
    }
}

@Composable
fun ReceiptImage(imageName: String) {
    val context = LocalContext.current
    val languageManager = LocalLanguageManager.current
    val imageFile = remember(imageName) {
        File(File(context.filesDir, "receipts"), imageName)
    }

    if (imageFile.exists()) {
        var expanded by remember { mutableStateOf(true) }
        
        Card(
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ReceiptLong,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = languageManager.getString("receipt_photo"),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand"
                    )
                }

                if (expanded) {
                    Box(modifier = Modifier.fillMaxWidth().height(450.dp)) {
                        ZoomableAsyncImage(
                            model = imageFile,
                            contentDescription = "Receipt Image",
                            state = rememberZoomableImageState(),
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
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
