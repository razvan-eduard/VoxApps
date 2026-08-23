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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.filled.BurstMode
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.runtime.mutableStateSetOf
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
import androidx.compose.ui.unit.sp
import com.voxapps.design.category.VoxCategoryFields
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast
import com.voxapps.attachments.AttachmentFileStore
import com.voxapps.attachments.AttachmentSource
import com.voxapps.attachments.ui.AttachmentUiItem
import com.voxapps.attachments.ui.AttachmentsSection
import com.voxapps.attachments.ui.GroupDeleteConfig
import com.voxapps.attachments.ui.rememberCameraCaptureLauncher
import com.voxapps.attachments.ui.rememberVisionInstalled
import com.voxapps.attachments.ui.rememberVisionCaptureLauncher
import com.voxapps.design.PaperTapField
import com.voxapps.design.openLocationInMaps
import com.voxapps.design.SpeedDialAction
import com.voxapps.location.ui.VoxLocationField
import com.voxapps.ipc.VoxOcrRequest
import com.voxapps.design.picklist.Picklist
import com.voxapps.expenses.ExpensesApplication
import com.voxapps.expenses.data.ExpensesAttachments
import com.voxapps.expenses.data.Category
import com.voxapps.expenses.data.DUPLICATE_ENTRY_RESULT
import com.voxapps.expenses.data.Expense
import com.voxapps.expenses.data.ExpenseLineItem
import com.voxapps.expenses.data.ExpenseSanitizer
import com.voxapps.expenses.data.ExpenseWithDetails
import com.voxapps.expenses.data.NEAR_DUPLICATE_MERGED_RESULT
import com.voxapps.design.settings.VoxSuggestionChip
import com.voxapps.expenses.data.ExpenseSuggestionTarget
import com.voxapps.suggestions.OfferedSuggestion
import com.voxapps.expenses.data.PendingLineItemsJson
import com.voxapps.expenses.data.TransactionDirection
import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.expenses.domain.llm.ExpenseAmountMismatch
import com.voxapps.expenses.domain.llm.ExpenseScanCleanupRequestSender
import com.voxapps.expenses.domain.llm.ExpenseScanRequestSender
import com.voxapps.expenses.domain.llm.LlmTasks
import com.voxapps.expenses.domain.llm.MultimodalAttachmentResolver
import com.voxapps.expenses.data.preferences.ExpensesSettingsRepository
import com.voxapps.expenses.domain.location.resolveCurrentCityName
import com.voxapps.expenses.domain.localization.LanguageManager
import com.voxapps.expenses.state.ExpensesStateManager
import com.voxapps.datahygiene.DirtyField
import com.voxapps.datahygiene.RecordSource
import com.voxapps.datahygiene.SaveDecision
import com.voxapps.datahygiene.decideForSave
import com.voxapps.textmatch.FuzzyNameMatcher
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Date
import java.util.UUID
import com.voxapps.design.color.VoxColorPalette

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

/** Everything a back-press dirty-check needs to compare — see `isDirty()` in ExpenseEditScreen. */
private data class EditSnapshot(
    val title: String,
    val totalText: String,
    val currency: String,
    val vendor: String,
    val bank: String,
    val location: String,
    val comments: String,
    val dateTime: Long,
    val categoryId: Long?,
    val direction: TransactionDirection,
    val items: List<LineItemDraft>
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
    /** Resolved by [com.voxapps.expenses.domain.llm.VatDisplay] — the setting alone cannot answer
     *  it, since the middle setting asks the record rather than the user. */
    vatDisplayEnabled: Boolean,
    /** True only where the columns are switched off and this record turned out to carry a
     *  breakdown: the one combination that would otherwise discard something silently. */
    vatFoundButHidden: Boolean = false,
    decimalSeparator: String,
    locationPrefillEnabled: Boolean,
    settingsRepository: ExpensesSettingsRepository,
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
            val city = resolveCurrentCityName(context, settingsRepository)
            if (city != null && location.isBlank()) location = city
        }
    }
    var comments by remember { mutableStateOf(existing?.expense?.comments ?: "") }
    var dateTime by remember { mutableStateOf(existing?.expense?.dateTime ?: System.currentTimeMillis()) }
    var categoryId by remember { mutableStateOf(existing?.expense?.categoryId) }
    // From a line-items rescan on this (already-saved) expense — see PendingFieldSuggestion's doc
    // comment. Null on a brand-new expense (nothing to observe yet). Only fields differing from the
    // current local value below render a chip; tapping one sets the local field to match, which
    // makes that field's diff go to false on the next recomposition — the chip just disappears, no
    // separate "applied" tracking needed.
    // remember(...) here is load-bearing, not style: without it this Flow is a NEW object every
    // recomposition, and collectAsStateWithLifecycle's internal produceState is keyed on the Flow
    // instance — a fresh object every recomposition cancels and restarts the collection every single
    // recomposition, which made this look like it never updated on an already-open screen (confirmed
    // bug report: neither the line-items nor any other field's suggestion ever appeared live).
    val pendingSuggestionFlow = remember(existing?.expense?.id) {
        existing?.expense?.id?.let { stateManager.observeSuggestions(it) } ?: emptyFlow()
    }
    val offeredSuggestions by pendingSuggestionFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    // Keyed by field: the screen reads what it needs by name and never has to know what else was
    // offered.
    //
    // Taking one of these puts its value in the draft below and nowhere else. This screen reaches
    // the database through Save and through nothing else, so a proposal accepted and then abandoned
    // is abandoned with the rest of the edit — see ExpenseSuggestionTarget, which refuses to write
    // for the same reason.
    val suggested = remember(offeredSuggestions) {
        offeredSuggestions.associate { it.field.key to it.proposed }
    }
    val suggestionSource = remember(offeredSuggestions) {
        offeredSuggestions.firstOrNull { it.sourceTag != null }?.sourceTag
    }
    // Per-field "no thanks" — tapping a chip's x hides just that field's suggestion without applying
    // it. Keyed on the whole pendingSuggestion object so a genuinely new rescan (a new DB row) starts
    // with a clean slate rather than inheriting dismissals aimed at the old suggestion's values.
    val dismissedSuggestionFields = remember(offeredSuggestions) { mutableStateSetOf<String>() }
    // Attachment groups added (by a Vision capture or gallery multi-pick) during THIS screen visit —
    // tracked so "Discard" can remove them along with the pending suggestion instead of leaving newly
    // scanned photos permanently attached to a record the user chose not to keep changes on. A capture
    // session's photos land via OcrResultReceiver's own broadcast handling (see
    // ExpenseAttachmentsSection's takePhotoSingle/Stitch/Batch doc comment), with no direct callback
    // into this screen for "a new group was just added" — so this compares each attachments emission
    // against whatever was already there the first time this screen saw them instead.
    val attachmentsFlow = remember(existing?.expense?.id) {
        existing?.expense?.id?.let { stateManager.observeAttachments(it) } ?: emptyFlow()
    }
    val currentAttachments by attachmentsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val knownGroupIdsAtOpen = remember { mutableStateOf<Set<String>?>(null) }
    val sessionAddedGroupIds = remember { mutableStateListOf<String>() }
    // Establishes the baseline from one real, awaited DB read — NOT from currentAttachments' state
    // above, whose collectAsStateWithLifecycle(initialValue = emptyList()) placeholder fires
    // (synchronously, before the Flow's real first emission) on every composition. Using that as the
    // baseline made reopening a record that already had a saved stitch group treat the whole
    // (pre-existing, legitimate) group as "added this session" — confirmed bug: Save, reopen, tap
    // back with nothing touched, and the discard-confirm dialog still offered to delete those old
    // photos. A direct read of the same rows always returns their true current value.
    LaunchedEffect(existing?.expense?.id) {
        knownGroupIdsAtOpen.value = existing?.expense?.id?.let { id ->
            stateManager.getAttachments(id).mapNotNull { it.groupId }.toSet()
        } ?: emptySet()
    }
    LaunchedEffect(currentAttachments, knownGroupIdsAtOpen.value) {
        val groupIdsNow = currentAttachments.mapNotNull { it.groupId }.toSet()
        val known = knownGroupIdsAtOpen.value
        if (known == null) {
            // Baseline not established yet — wait for it rather than guessing.
        } else {
            (groupIdsNow - known).forEach { if (it !in sessionAddedGroupIds) sessionAddedGroupIds.add(it) }
        }
    }
    var showNewCategoryDialog by remember { mutableStateOf(false) }
    var direction by remember { mutableStateOf(existing?.expense?.direction ?: TransactionDirection.OUTGOING) }
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
        if (existing != null) {
            // Genuine manual edits only — voice/scan/notification capture never routes through
            // this screen, so these observers see exactly what a human changed by hand.
            stateManager.recordFieldEditPatterns(existing, expense)
            stateManager.recordFieldCorrections(existing, expense, lineItems)
            stateManager.updateExpense(expense, lineItems)
            // Whatever the rescan suggested is now moot either way — applied suggestions are
            // already reflected in `expense`, and anything left un-applied shouldn't linger past
            // this save to be offered again against a record that's moved on.
            stateManager.clearSuggestions(expense.id)
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

    // Exact name+price duplicates are already merged before items ever reach this screen (see
    // ExpenseParseResultParser.dedupeItems) — this only flags NAME-similar rows that weren't merged
    // (different price, or a near-miss OCR spelling), which is deliberately ambiguous enough that a
    // human should look rather than have it silently merged or silently ignored.
    val possibleDuplicateIndices = remember(items.toList()) {
        val flagged = mutableSetOf<Int>()
        for (i in items.indices) {
            val nameI = items[i].name
            if (nameI.isBlank()) continue
            for (j in i + 1 until items.size) {
                val nameJ = items[j].name
                if (nameJ.isBlank()) continue
                if (FuzzyNameMatcher.namesMatch(nameI, nameJ)) {
                    flagged += i
                    flagged += j
                }
            }
        }
        flagged
    }
    val totalMismatch = items.isNotEmpty() &&
        (parseDecimalOrNull(totalText, useComma)?.let { ExpenseAmountMismatch.isMismatch(it, itemsSum) } ?: false)

    // Snapshot of every editable field at screen-open time, so back can tell whether there's
    // actually anything to prompt about — closing an untouched screen (just viewing, or a
    // freshly-created blank draft) shouldn't interrupt with a dialog.
    val initialSnapshot = remember {
        EditSnapshot(title, totalText, currency, vendor, bank, location, comments, dateTime, categoryId, direction, items.toList())
    }
    fun isDirty(): Boolean =
        EditSnapshot(title, totalText, currency, vendor, bank, location, comments, dateTime, categoryId, direction, items.toList()) != initialSnapshot ||
            // A pending suggestion row surviving means there's still something Discard needs to
            // clear even if no field's been touched — a chip left untapped changes no local value,
            // so the diff above alone would silently miss it (the exact bug: chips visible, back
            // closes with no prompt, suggestion row untouched, still there on reopen).
            offeredSuggestions.isNotEmpty() ||
            // Same reasoning for photos captured/picked this session (see sessionAddedGroupIds' doc
            // comment) — they're written straight to the DB, not staged, so nothing in the
            // EditSnapshot diff above ever reflects them. Without this, a session that only ever
            // added a scan (rescan still pending, or never landed at all — e.g. it crashed) reads as
            // "not dirty", back skips the confirm dialog entirely, and the photos are stuck attached
            // with no chance to discard them.
            sessionAddedGroupIds.isNotEmpty()
    var showDiscardConfirm by remember { mutableStateOf(false) }

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
            // Nothing is actually being saved here (no valid total), so this is a discard in
            // everything but name — same sessionAddedGroupIds cleanup as the real Discard path (see
            // isDirty()'s doc comment), otherwise photos captured this visit orphan permanently.
            existing?.expense?.id?.let { expenseId ->
                sessionAddedGroupIds.forEach { groupId -> stateManager.deleteAttachmentGroup(expenseId, groupId, context) }
            }
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

    // Back arrow / system back / gesture: prompt only if something actually changed since opening —
    // an untouched screen (nothing typed, or just viewing an existing expense) closes straight away,
    // same as before this dialog existed.
    fun handleBackPress() {
        if (isDirty()) {
            showDiscardConfirm = true
        } else {
            discardPendingAttachments(pendingAttachments, context)
            onDone()
        }
    }

    BackHandler { handleBackPress() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(languageManager.getString(if (existing != null) "edit_expense" else "new_expense")) },
                navigationIcon = {
                    IconButton(onClick = ::handleBackPress) {
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
                    ExpenseAttachmentsSection(existing.expense.id, imageName, stateManager, offeredSuggestions)
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
                        onValueChange = { title = it },
                        suggestion = suggested[ExpenseSuggestionTarget.KEY_TITLE]?.takeIf { it != title && "title" !in dismissedSuggestionFields }?.let { suggested ->
                            { FieldSuggestionChip(suggested, onDismiss = { dismissedSuggestionFields += "title" }) { title = suggested } }
                        }
                    )
                    PaperField(
                        label = languageManager.getString("expense_vendor"),
                        value = vendor,
                        onValueChange = { vendor = it },
                        suggestion = suggested[ExpenseSuggestionTarget.KEY_VENDOR]?.takeIf { it != vendor && "vendor" !in dismissedSuggestionFields }?.let { suggested ->
                            { FieldSuggestionChip(suggested, onDismiss = { dismissedSuggestionFields += "vendor" }) { vendor = suggested } }
                        }
                    )
                    PaperField(
                        label = languageManager.getString("expense_bank"),
                        value = bank,
                        onValueChange = { bank = it },
                        suggestion = suggested[ExpenseSuggestionTarget.KEY_BANK]?.takeIf { it != bank && "bank" !in dismissedSuggestionFields }?.let { suggested ->
                            { FieldSuggestionChip(suggested, onDismiss = { dismissedSuggestionFields += "bank" }) { bank = suggested } }
                        }
                    )
                    // Search-first entry (OpenStreetMap place search + GPS lock). The GPS lambda
                    // routes through resolveCurrentCityName — live fix, then the TTL'd cache,
                    // then Home Town, the exact chain commander uses; the rescan suggestion chip
                    // keeps its slot above the field.
                    suggested[ExpenseSuggestionTarget.KEY_LOCATION]?.takeIf { it != location && "location" !in dismissedSuggestionFields }?.let { suggested ->
                        FieldSuggestionChip(suggested, onDismiss = { dismissedSuggestionFields += "location" }) { location = suggested }
                    }
                    VoxLocationField(
                        value = location,
                        onValueChange = { location = it },
                        label = languageManager.getString("expense_location"),
                        clearContentDescription = languageManager.getString("cancel"),
                        gpsLock = {
                            com.voxapps.expenses.domain.location.resolveCurrentCityName(
                                context.applicationContext,
                                (context.applicationContext as com.voxapps.expenses.ExpensesApplication).container.settingsRepository
                            )
                        },
                        onOpenLocation = { openLocationInMaps(context, it) }
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
                        },
                        suggestion = suggested[ExpenseSuggestionTarget.KEY_DATE_TIME]?.toLongOrNull()?.takeIf { it != dateTime && "dateTime" !in dismissedSuggestionFields }?.let { suggested ->
                            {
                                FieldSuggestionChip(
                                    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(suggested)),
                                    onDismiss = { dismissedSuggestionFields += "dateTime" }
                                ) {
                                    dateTime = suggested
                                }
                            }
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
                    // Only offered when the suggested name matches an EXISTING category
                    // (case-insensitive) — no auto-create here, unlike the voice/scan-create
                    // paths, to keep applying a suggestion a synchronous, no-surprises action.
                    val suggestedCategory = suggested[ExpenseSuggestionTarget.KEY_CATEGORY]?.let { name ->
                        categories.firstOrNull { it.name.equals(name, ignoreCase = true) }
                    }?.takeIf { it.id != categoryId && "category" !in dismissedSuggestionFields }

                    Picklist(
                        items = categories,
                        selected = categories.firstOrNull { it.id == categoryId },
                        itemLabel = { it.labelled() },
                        onSelect = { categoryId = it.id },
                        noneLabel = languageManager.getString("none"),
                        onNoneSelected = { categoryId = null },
                        // Not a category, so not a row that selects one: it opens the dialog that
                        // makes a new one.
                        actionLabel = languageManager.getString("new_category_dropdown_item"),
                        onAction = { showNewCategoryDialog = true },
                        // The dot, never the icon: the label already carries the icon, and this menu's
                        // anchor has no slot of its own — so the colour is the one thing the row can
                        // add that the collapsed field cannot show.
                        itemLeading = { cat ->
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .clip(CircleShape)
                                    .background(CategoryColors.fromStored(cat.colorArgb))
                            )
                        },
                        anchor = { value, onClick ->
                            PaperTapField(
                                label = languageManager.getString("expense_category"),
                                value = value,
                                onClick = onClick,
                                trailingIcon = {
                                    Icon(
                                        Icons.Filled.ExpandMore,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                suggestion = suggestedCategory?.let { cat ->
                                    { FieldSuggestionChip(cat.labelled(), onDismiss = { dismissedSuggestionFields += "category" }) { categoryId = cat.id } }
                                }
                            )
                        }
                    )
                    PaperField(
                        label = languageManager.getString("expense_comments"),
                        value = comments,
                        onValueChange = { comments = it },
                        singleLine = false,
                        minLines = 2,
                        suggestion = suggested[ExpenseSuggestionTarget.KEY_COMMENTS]?.takeIf { it != comments && "comments" !in dismissedSuggestionFields }?.let { suggested ->
                            { FieldSuggestionChip(suggested, onDismiss = { dismissedSuggestionFields += "comments" }) { comments = suggested } }
                        }
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
                            modifier = Modifier.weight(1f),
                            suggestion = suggested[ExpenseSuggestionTarget.KEY_AMOUNT]?.toDoubleOrNull()
                                ?.let { formatDecimal(it, useComma) }
                                ?.takeIf { it != totalText && "totalAmount" !in dismissedSuggestionFields }
                                ?.let { suggested ->
                                    { FieldSuggestionChip(suggested, onDismiss = { dismissedSuggestionFields += "totalAmount" }) { totalText = suggested } }
                                }
                        )
                        PaperField(
                            label = languageManager.getString("expense_currency"),
                            value = currency,
                            onValueChange = { currency = it.uppercase().take(3) },
                            modifier = Modifier.weight(0.6f),
                            suggestion = suggested[ExpenseSuggestionTarget.KEY_CURRENCY]?.takeIf { it != currency && "currencyCode" !in dismissedSuggestionFields }?.let { suggested ->
                                { FieldSuggestionChip(suggested, onDismiss = { dismissedSuggestionFields += "currencyCode" }) { currency = suggested } }
                            }
                        )
                    }
                    if (totalMismatch) {
                        Text(
                            "${languageManager.getString("expense_total_mismatch")} (${formatDecimal(itemsSum, useComma)})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Picklist(
                        items = listOf(TransactionDirection.OUTGOING, TransactionDirection.INCOMING),
                        selected = direction,
                        itemLabel = {
                            languageManager.getString(
                                if (it == TransactionDirection.OUTGOING) "transaction_direction_outgoing"
                                else "transaction_direction_incoming"
                            )
                        },
                        onSelect = { direction = it },
                        anchor = { value, onClick ->
                            PaperTapField(
                                label = languageManager.getString("expense_direction"),
                                value = value,
                                onClick = onClick,
                                trailingIcon = {
                                    Icon(
                                        Icons.Filled.ExpandMore,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            )
                        }
                    )
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

                    // Line items found by a rescan (see PendingFieldSuggestion.itemsJson's doc
                    // comment) — a full-list "apply all" action, not a per-field diff like the
                    // scalar fields above, since there's no meaningful per-item comparison against
                    // whatever's already in the draft. appliedItemsJson (NOT remember(key)-scoped —
                    // deliberately a single persistent slot, compared by value against the current
                    // suggestion) hides the banner once tapped/dismissed for that exact itemsJson
                    // content; a genuinely new suggestion (different itemsJson) still shows it again.
                    val suggestedItems = remember(suggested[ExpenseSuggestionTarget.KEY_ITEMS]) {
                        PendingLineItemsJson.decode(suggested[ExpenseSuggestionTarget.KEY_ITEMS])
                    }
                    var appliedItemsJson by remember { mutableStateOf<String?>(null) }
                    if (suggestedItems.isNotEmpty() && suggested[ExpenseSuggestionTarget.KEY_ITEMS] != appliedItemsJson) {
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                languageManager.getString("lineitems_suggestion_found"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            FieldSuggestionChip(
                                String.format(languageManager.getString("lineitems_suggestion_apply_n"), suggestedItems.size),
                                onDismiss = {
                                    // Dismissing the suggestion also removes the scan that produced it
                                    // (see PendingFieldSuggestion.sourceGroupId's doc comment) — the
                                    // user rejected the rescan's findings, so the photos it was based on
                                    // shouldn't linger attached with no suggestion left to act on them.
                                    val groupId = suggestionSource
                                    if (existing != null && groupId != null) {
                                        stateManager.deleteAttachmentGroup(existing.expense.id, groupId, context)
                                    }
                                    existing?.expense?.id?.let { stateManager.clearSuggestions(it) }
                                    appliedItemsJson = suggested[ExpenseSuggestionTarget.KEY_ITEMS]
                                }
                            ) {
                                suggestedItems.forEach { parsedItem ->
                                    items.add(
                                        LineItemDraft(
                                            name = parsedItem.name,
                                            quantityText = formatDecimal(parsedItem.quantity, useComma),
                                            unitPriceText = formatDecimal(parsedItem.unitPrice, useComma),
                                            netAmountText = parsedItem.netAmount?.let { formatDecimal(it, useComma) } ?: "",
                                            vatAmountText = parsedItem.vatAmount?.let { formatDecimal(it, useComma) } ?: "",
                                            grossAmountText = parsedItem.grossAmount?.let { formatDecimal(it, useComma) } ?: ""
                                        )
                                    )
                                }
                                appliedItemsJson = suggested[ExpenseSuggestionTarget.KEY_ITEMS]
                            }
                        }
                    }

                    if (possibleDuplicateIndices.isNotEmpty()) {
                        Text(
                            languageManager.getString("possible_duplicate_items_warning"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }

                    if (items.isNotEmpty()) {
                        LineItemHeaderRow(vatDisplayEnabled, languageManager)
                    }

                    // The one case the setting would otherwise lose quietly: the columns are off,
                    // and this document turned out to carry a breakdown. Said once, here, rather
                    // than discarded — accepting it switches the columns on for good.
                    if (vatFoundButHidden) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                languageManager.getString("vat_found_hidden"),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(onClick = { stateManager.setVatDisplay(ExpensesSettings.VAT_ON) }) {
                                Text(languageManager.getString("vat_found_hidden_add"))
                            }
                        }
                    }

                    items.forEachIndexed { index, draftItem ->
                        LineItemCard(
                            item = draftItem,
                            vatDisplayEnabled = vatDisplayEnabled,
                            languageManager = languageManager,
                            possibleDuplicate = index in possibleDuplicateIndices,
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

    if (showDiscardConfirm) {
        ConfirmDiscardDialog(
            onSave = {
                showDiscardConfirm = false
                attemptSaveAndClose()
            },
            onDiscard = {
                showDiscardConfirm = false
                discardPendingAttachments(pendingAttachments, context)
                // Photos captured/picked during this visit are exactly the kind of not-yet-committed
                // change "discard" should throw away too (see sessionAddedGroupIds' doc comment) —
                // without this they stayed permanently attached even though the scan they existed for
                // was rejected.
                existing?.expense?.id?.let { expenseId ->
                    sessionAddedGroupIds.forEach { groupId -> stateManager.deleteAttachmentGroup(expenseId, groupId, context) }
                }
                // A pending rescan suggestion (chips + line-items banner) is exactly the kind of
                // not-yet-applied change "discard" should throw away too — otherwise it just
                // reappears next time this record is opened, since it's a separate DB row that
                // saving (not discarding) is the only other thing that clears.
                existing?.expense?.id?.let { stateManager.clearSuggestions(it) }
                onDone()
            },
            onCancel = { showDiscardConfirm = false }
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
            onConfirm = { name, color, icon ->
                stateManager.addCategory(name, color, icon, onResult = { newId -> if (newId > 0) categoryId = newId })
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
    onConfirm: (name: String, colorArgb: Long, icon: String?) -> Unit
) {
    val languageManager = LocalLanguageManager.current
    var name by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf<String?>(null) }
    var selectedColor by remember(existingColors, precedingColor) {
        mutableStateOf(VoxColorPalette.unusedOrRandomColor(existingColors, precedingColor))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(languageManager.getString("new_category_dialog_title")) },
        text = {
            VoxCategoryFields(
                name = name,
                onNameChange = { name = it },
                icon = icon,
                onIconChange = { icon = it },
                color = selectedColor,
                onColorChange = { selectedColor = it },
                strings = rememberCategoryFieldStrings()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim(), selectedColor, icon) },
                enabled = name.isNotBlank()
            ) {
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

/** No more picker — retry now always operates on everything currently attached to this never-
 *  reviewed stub, exactly like rescan operates on a photo's whole group elsewhere: there is nothing
 *  left to distinguish "the original scan" from "a manually-added photo," since every Expenses
 *  attachment already gets its own OCR text at capture time. [imageName] is only still meaningful for
 *  a legacy (pre-attachment-model) stub that has no manual attachments at all — its OCR text was
 *  already saved next to that scan photo at creation time, so retrying it needs no fresh Vision round
 *  trip; a stub with manual attachments retries all of them together instead. */
@Composable
private fun StubRetryBanner(expenseId: Long, imageName: String?, stateManager: ExpensesStateManager, onDone: () -> Unit) {
    val context = LocalContext.current
    val languageManager = LocalLanguageManager.current
    val scope = rememberCoroutineScope()
    var retrying by remember { mutableStateOf(false) }
    val manualAttachments by stateManager.observeAttachments(expenseId).collectAsStateWithLifecycle(initialValue = emptyList())

    fun sendRetry() {
        if (manualAttachments.isNotEmpty()) {
            // Fires a fresh headless-OCR retry request per attachment — OcrResultReceiver's
            // isRetryWithPhoto branch waits for every one of them, then combines the whole batch
            // (--- Page N --- separated, same shape as a group rescan) into one direct-overwrite.
            retrying = true
            manualAttachments.forEach { entity ->
                val uri = AttachmentFileStore.uriFor(context, ExpensesAttachments.FILE_PROVIDER_AUTHORITY, ExpensesAttachments.DIR, entity.fileName)
                ExpenseScanRequestSender.sendHeadlessRetryOcr(context, expenseId, ExpensesAttachments.DIR, entity.fileName, uri)
            }
        } else if (imageName != null) {
            val rawTextFile = File(File(context.filesDir, "receipts"), imageName.substringBeforeLast('.') + ".txt")
            val rawText = rawTextFile.takeIf { it.exists() }?.readText()
            if (rawText.isNullOrBlank()) {
                Toast.makeText(context, languageManager.getString("retry_cleanup_no_saved_text"), Toast.LENGTH_LONG).show()
                return
            }
            retrying = true
            val container = (context.applicationContext as ExpensesApplication).container
            scope.launch {
                // Same staged AI copy OcrResultReceiver already prepared for the original scan (if
                // Vision's own setting produced one) — gated on its own attachPhotoOnRetry toggle,
                // separate from attachPhotoOnScan (see ExpensesSettings' doc comments for why retry is
                // treated as a distinct decision).
                val attachOnRetry = container.settingsRepository.getSnapshot().attachPhotoOnRetry
                val attachmentUri = MultimodalAttachmentResolver.resolve(context, imageName, attachOnRetry)
                ExpenseScanCleanupRequestSender.send(
                    context, container, rawText, imageName, retryOfExpenseId = expenseId, attachmentUri = attachmentUri
                )
            }
        } else {
            Toast.makeText(context, languageManager.getString("retry_cleanup_no_saved_text"), Toast.LENGTH_LONG).show()
            return
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
            Button(enabled = !retrying, onClick = { sendRetry() }) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(languageManager.getString("retry_cleanup"))
            }
        }
    }
}

/** Unifies the original receipt scan (if any — first, non-removable) with manually-added
 *  attachments (see :core:attachments) into one section, per the shared AttachmentsSection UI. */
@Composable
private fun ExpenseAttachmentsSection(
    expenseId: Long,
    receiptImageName: String?,
    stateManager: ExpensesStateManager,
    offeredSuggestions: List<OfferedSuggestion>
) {
    val languageManager = LocalLanguageManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manualEntities by stateManager.observeAttachments(expenseId).collectAsStateWithLifecycle(initialValue = emptyList())
    // Blocks a second rescan trigger (manual icon or auto-trigger-on-first-photo) while one is
    // already in flight for this expense — without this, rescanning two different attachments back
    // to back races two LLM replies against PendingFieldSuggestion's single per-expense row, and
    // whichever lands second silently discards everything the first one found (REPLACE, not merge).
    // Cleared once a fresh suggestion reply actually lands (pendingSuggestion changes); the timeout
    // below is a safety net so a lost/failed request (Commander crash, dropped broadcast) doesn't
    // leave rescanning permanently blocked for the rest of this screen visit.
    var rescanInFlight by remember { mutableStateOf(false) }
    LaunchedEffect(offeredSuggestions) {
        rescanInFlight = false
    }
    LaunchedEffect(rescanInFlight) {
        if (rescanInFlight) {
            delay(45_000)
            rescanInFlight = false
        }
    }
    val items = remember(receiptImageName, manualEntities) {
        val groupSizes = manualEntities.mapNotNull { it.groupId }.groupingBy { it }.eachCount()
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
                    val groupSize = e.groupId?.let { groupSizes[it] } ?: 0
                    AttachmentUiItem(
                        id = e.id,
                        uri = AttachmentFileStore.uriFor(context, ExpensesAttachments.FILE_PROVIDER_AUTHORITY, ExpensesAttachments.DIR, e.fileName),
                        removable = true,
                        groupLabel = if (groupSize > 1) "${e.groupOrder + 1}/$groupSize" else null,
                        groupKey = e.groupId,
                        groupSource = e.source
                    )
                }
            )
        }
    }
    // Extracts fields/line items from an attached photo into this already-saved expense — the photo
    // may have been added well after the expense was created (voice/notification-created expenses
    // have no receipt at all). Vision OCRs it headlessly first, no camera UI (see
    // ExpenseScanRequestSender.sendHeadlessRescan) — OcrResultReceiver's EXPENSE_LINEITEMS_RESCAN
    // branch then sends that text, plus the photo itself gated on attachPhotoOnRetry, to the LLM: the
    // exact same two-step "OCR then LLM" shape "Retry cleanup" already uses, just staged as a review
    // suggestion on reply instead of a direct overwrite (see LlmTasks.EXPENSE_LINEITEMS_RESCAN's doc
    // comment for why). Shared by the manual per-attachment chip and the auto-trigger setting below.
    // Takes every group member at once (not one call per photo) since [rescanInFlight] gates one
    // LOGICAL rescan operation per expense at a time — a group's photos all belong to the same
    // operation, so they share a single guard check/set instead of each racing it independently
    // (which would silently drop every member after the first). See OcrResultReceiver's
    // EXPENSE_LINEITEMS_RESCAN branch for how the resulting per-photo OCR replies get recombined.
    fun triggerRescan(dirName: String, fileNames: List<String>, silent: Boolean) {
        if (fileNames.isEmpty()) return
        if (rescanInFlight) {
            if (!silent) {
                Toast.makeText(context, languageManager.getString("rescan_lineitems_already_in_progress"), Toast.LENGTH_SHORT).show()
            }
            return
        }
        rescanInFlight = true
        fileNames.forEach { fileName ->
            val uri = AttachmentFileStore.uriFor(context, ExpensesAttachments.FILE_PROVIDER_AUTHORITY, dirName, fileName)
            ExpenseScanRequestSender.sendHeadlessRescan(context, expenseId, dirName, fileName, uri)
        }
        if (!silent) {
            Toast.makeText(context, languageManager.getString("rescan_lineitems_started"), Toast.LENGTH_SHORT).show()
        }
    }

    fun rescanAttachmentForLineItems(item: AttachmentUiItem) {
        if (item.id == -1L) {
            receiptImageName?.let { triggerRescan("receipts", listOf(it), silent = false) }
        } else {
            val tapped = manualEntities.firstOrNull { it.id == item.id } ?: return
            val groupFileNames = tapped.groupId?.let { gid ->
                manualEntities.filter { it.groupId == gid }.sortedBy { it.groupOrder }.map { it.fileName }
            } ?: listOf(tapped.fileName)
            triggerRescan(ExpensesAttachments.DIR, groupFileNames, silent = false)
        }
    }

    fun settingsSnapshot() = (context.applicationContext as ExpensesApplication).container.settingsRepository.getSnapshot()
    fun autoRescanEnabled() = settingsSnapshot().autoRescanOnFirstAttachment

    // Gallery multi-select: several photos picked at once become one group (a single pick stays
    // groupId = null, identical to today's one-photo attach). `wasEmpty`/whether to auto-rescan is
    // decided once for the whole batch — synchronous within this one call, so there's no Flow-timing
    // gap to worry about (unlike the live-capture burst below, where shots land asynchronously across
    // several recompositions).
    fun handlePickedUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val wasEmpty = items.isEmpty()
        val groupId = if (uris.size > 1) UUID.randomUUID().toString() else null
        val stagedFileNames = uris.mapIndexedNotNull { index, uri ->
            AttachmentFileStore.stage(context, uri, ExpensesAttachments.DIR)?.also { fileName ->
                stateManager.addManualAttachment(expenseId, fileName, groupId, index)
            }
        }
        if (wasEmpty && stagedFileNames.isNotEmpty() && autoRescanEnabled()) {
            triggerRescan(ExpensesAttachments.DIR, stagedFileNames, silent = true)
        }
    }

    // OcrResultReceiver's EXPENSE_ATTACHMENT_CAPTURE branch now owns the whole "stage the captured
    // photo(s), OCR whatever doesn't already have text, and — only when this was the expense's very
    // first attachment and auto-rescan is on — auto-combine+send" flow entirely on its own (mirrors
    // handlePickedUris' own wasEmpty/autoRescanEnabled gate for a gallery pick). Nothing left for this
    // composable to react to once capture finishes — Vision's own capture session owns the whole
    // "keep shooting" loop too, so these calls are just "launch and forget."
    val takePhotoSingle = rememberVisionCaptureLauncher(
        baseTask = "${LlmTasks.EXPENSE_ATTACHMENT_CAPTURE}:$expenseId", hint = null, produceOCR = true,
        captureMode = VoxOcrRequest.CAPTURE_MODE_SINGLE, tableMode = true
    )
    val takePhotoStitch = rememberVisionCaptureLauncher(
        baseTask = "${LlmTasks.EXPENSE_ATTACHMENT_CAPTURE}:$expenseId", hint = null, produceOCR = true,
        captureMode = VoxOcrRequest.CAPTURE_MODE_STITCH, tableMode = true
    )
    val takePhotoBatch = rememberVisionCaptureLauncher(
        baseTask = "${LlmTasks.EXPENSE_ATTACHMENT_CAPTURE}:$expenseId", hint = null, produceOCR = true,
        captureMode = VoxOcrRequest.CAPTURE_MODE_BATCH, tableMode = true
    )
    // Zero attachments yet: offer single + stitch — either one triggers the "first attachment
    // updates the record" auto-flow above (a stitch group here is genuinely one document, one JSON —
    // see OcrResultReceiver.handleAttachmentCapture). Already has attachments: switch to single +
    // batch — a single addition just stages passively (no auto-rescan, matches today's behavior for
    // an n-th photo), while batch gives each new photo its own independent rescan suggestion, never
    // combined with the existing data (see the same receiver branch's batch sub-path).
    // Attaching a photo never requires Vision: the plain system camera is always first (it goes
    // through the same path as a gallery pick, rescan gate included), and Vision's cropped-document
    // modes (crop-rectangle icon) appear only while Vision is installed.
    val visionInstalled = rememberVisionInstalled()
    val takeStandardPhoto = rememberCameraCaptureLauncher(ExpensesAttachments.FILE_PROVIDER_AUTHORITY) { uri ->
        handlePickedUris(listOf(uri))
    }
    val captureActions = listOf(
        SpeedDialAction(Icons.Filled.PhotoCamera, languageManager.getString("attachment_take_photo"), takeStandardPhoto)
    )
    val visionActions = buildList {
        if (visionInstalled) {
            add(SpeedDialAction(Icons.Filled.Crop, languageManager.getString("capture_mode_single"), takePhotoSingle))
            if (items.isEmpty()) {
                add(SpeedDialAction(Icons.Filled.Layers, languageManager.getString("capture_mode_stitch"), takePhotoStitch))
            } else {
                add(SpeedDialAction(Icons.Filled.BurstMode, languageManager.getString("capture_mode_batch"), takePhotoBatch))
            }
        }
    }
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris -> handlePickedUris(uris) }

    var pendingRemoveAttachment by remember { mutableStateOf<AttachmentUiItem?>(null) }

    AttachmentsSection(
        title = languageManager.getString("attachments"),
        items = items,
        canAdd = manualEntities.size < 10,
        onPickFromGallery = {
            pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        captureActions = captureActions,
        visionActions = visionActions,
        galleryLabel = languageManager.getString("attachment_choose_gallery"),
        cancelLabel = languageManager.getString("cancel"),
        onRemove = { item -> pendingRemoveAttachment = item },
        modifier = Modifier.padding(bottom = 12.dp),
        onRescan = ::rescanAttachmentForLineItems,
        groupDelete = GroupDeleteConfig(
            onDeleteGroup = { groupId -> stateManager.deleteAttachmentGroup(expenseId, groupId, context) },
            confirmTitle = languageManager.getString("delete_attachment_group_title"),
            confirmMessage = languageManager.getString("delete_attachment_group_message"),
            confirmLabel = languageManager.getString("delete"),
            cancelLabel = languageManager.getString("cancel")
        )
    )

    pendingRemoveAttachment?.let { item ->
        ConfirmDeleteDialog(
            title = languageManager.getString("delete_attachment_title"),
            message = languageManager.getString("delete_attachment_message"),
            onConfirm = {
                manualEntities.firstOrNull { it.id == item.id }?.let { stateManager.removeAttachment(it, context) }
                pendingRemoveAttachment = null
            },
            onDismiss = { pendingRemoveAttachment = null }
        )
    }
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
    var pendingRemoveAttachment by remember { mutableStateOf<AttachmentUiItem?>(null) }
    AttachmentsSection(
        title = languageManager.getString("attachments"),
        items = items,
        canAdd = pendingAttachments.size < 10,
        onPickFromGallery = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        // Draft (not-yet-saved) expense attachments still use the plain system camera, not Vision —
        // out of scope for the single/stitch/batch speed dial (no OCR/record concept exists yet for
        // a draft), so this is just the one existing "take photo" action, unchanged behavior.
        captureActions = listOf(SpeedDialAction(Icons.Filled.PhotoCamera, languageManager.getString("attachment_take_photo"), takePhoto)),
        galleryLabel = languageManager.getString("attachment_choose_gallery"),
        cancelLabel = languageManager.getString("cancel"),
        onRemove = { item -> pendingRemoveAttachment = item },
        modifier = Modifier.padding(bottom = 12.dp)
    )
    pendingRemoveAttachment?.let { item ->
        ConfirmDeleteDialog(
            title = languageManager.getString("delete_attachment_title"),
            message = languageManager.getString("delete_attachment_message"),
            onConfirm = {
                pendingAttachments.firstOrNull { it.hashCode().toLong() == item.id }?.let { fileName ->
                    AttachmentFileStore.delete(context, ExpensesAttachments.DIR, fileName)
                    onChange(pendingAttachments - fileName)
                }
                pendingRemoveAttachment = null
            },
            onDismiss = { pendingRemoveAttachment = null }
        )
    }
}

/**
 * A suggestion beside the field it concerns. The chip itself is shared — see
 * [com.voxapps.design.settings.VoxSuggestionChip] for what its two colours mean; this only supplies
 * the width cap, since a merchant name has to sit next to a field without pushing it off screen.
 */
@Composable
private fun FieldSuggestionChip(
    value: String,
    onDismiss: (() -> Unit)? = null,
    asking: Boolean = false,
    onClick: () -> Unit
) {
    VoxSuggestionChip(
        label = value,
        asking = asking,
        onClick = onClick,
        onDismiss = onDismiss,
        dismissContentDescription = LocalLanguageManager.current.getString("dismiss_suggestion"),
        modifier = Modifier.padding(start = 6.dp).widthIn(max = if (onDismiss != null) 170.dp else 140.dp)
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
    dividerColor: Color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
    // A rescanned photo's suggested replacement for this field — see FieldSuggestionChip's doc
    // comment. Null (the default) renders nothing, so every other caller of this shared field is
    // unaffected.
    suggestion: (@Composable () -> Unit)? = null
) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f)) {
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
            suggestion?.invoke()
        }
        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = dividerColor, thickness = 1.dp)
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
private fun ConfirmDiscardDialog(onSave: () -> Unit, onDiscard: () -> Unit, onCancel: () -> Unit) {
    val languageManager = LocalLanguageManager.current
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(languageManager.getString("discard_changes_title")) },
        text = { Text(languageManager.getString("discard_changes_message")) },
        confirmButton = {
            TextButton(onClick = onSave) { Text(languageManager.getString("save")) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDiscard) {
                    Text(languageManager.getString("discard"), color = OffenseRed)
                }
                TextButton(onClick = onCancel) { Text(languageManager.getString("cancel")) }
            }
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
    possibleDuplicate: Boolean = false,
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
                        color = if (possibleDuplicate) MaterialTheme.colorScheme.error else Color.Unspecified,
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
