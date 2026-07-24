package com.voxapps.expenses.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.voxapps.expenses.ExpensesApplication
import com.voxapps.expenses.data.Expense
import com.voxapps.expenses.data.ExpenseLineItem
import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.expenses.data.NEAR_DUPLICATE_MERGED_RESULT
import com.voxapps.expenses.di.ExpensesContainer
import com.voxapps.expenses.domain.llm.CategoryMergeMappingParser
import com.voxapps.expenses.domain.llm.DuplicateGroup
import com.voxapps.expenses.domain.llm.ExpenseDeduplicationRequestSender
import com.voxapps.expenses.domain.llm.ExpenseDeduplicationResultParser
import com.voxapps.expenses.domain.llm.ExpenseSummary
import com.voxapps.expenses.domain.llm.ExpenseParseResultParser
import com.voxapps.expenses.domain.location.ExpensesLocationHelper
import com.voxapps.expenses.domain.llm.LlmTasks
import com.voxapps.expenses.domain.llm.NotificationExpenseParseResultParser
import com.voxapps.expenses.domain.llm.PendingNotificationExpense
import com.voxapps.expenses.domain.llm.PendingNotificationExpenseRepository
import com.voxapps.datahygiene.FieldCleaner
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxLlmRequestQueue
import com.voxapps.ipc.VoxLlmResult
import com.voxapps.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

private const val TAG = "LlmResultReceiver"

/**
 * Handles async replies from Commander's LLM hook. Extracts the physical receipt image name
 * from the task metadata (Task:ImageName) to ensure the file is linked to the final record.
 */
class LlmResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != VoxIpc.ACTION_LLM_RESULT) return
        val result = VoxLlmResult.fromJson(intent.getStringExtra(VoxIpc.EXTRA_LLM_PAYLOAD)) ?: return
        val container = (context.applicationContext as ExpensesApplication).container

        // Strip the trailing requestId VoxLlmRequestQueue.enqueueAndSend appended (if this request
        // was routed through the queue at all — an un-tagged task is returned unchanged) before any
        // of the existing fixed-position segment parsing below, which must see exactly what each
        // sender originally built.
        val (task, requestId) = VoxLlmRequestQueue.splitRequestId(result.task)

        // Recover task and optional physical asset name (format "TASK:IMAGE_NAME" or, for a stub
        // retry, "TASK:IMAGE_NAME:RETRY_OF_EXPENSE_ID").
        val taskParts = task.split(":")
        val baseTask = taskParts[0]
        val storedImageName = taskParts.getOrNull(1)
        val retryOfExpenseId = taskParts.getOrNull(2)?.toLongOrNull()

        android.util.Log.println(android.util.Log.ASSERT, TAG, "LLM result: status=${result.status} task=${result.task} baseTask=$baseTask imageName=$storedImageName")
        if (result.rawJson != null) {
            android.util.Log.println(android.util.Log.ASSERT, TAG, "LLM rawJson length: ${result.rawJson!!.length}")
        }

        when (baseTask) {
            LlmTasks.EXPENSE_PARSE, LlmTasks.EXPENSE_SCAN_CLEANUP -> {
                val rawJson = result.rawJson
                val isSuccess = result.status == VoxLlmResult.STATUS_SUCCESS && rawJson != null
                val parsed = if (isSuccess) ExpenseParseResultParser.parse(rawJson!!) else null
                
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        if (requestId != null) container.pendingLlmRequestQueue.markFulfilled(requestId)
                        if (parsed != null && retryOfExpenseId != null) {
                            // Retry succeeded: update the existing stub row in place rather than
                            // inserting a new one (which would duplicate the expense and orphan
                            // the original stub).
                            updateExpenseFromRetry(context.applicationContext, container, parsed, retryOfExpenseId)
                        } else if (parsed != null) {
                            val newId = createExpenseFromParsed(context.applicationContext, container, parsed, storedImageName)
                            // Scan-specific — a voice-created expense keeps today's behavior (an
                            // optional save toast, no forced navigation). Commander's cleanup is
                            // async, so this is the earliest point Expenses can actually know the
                            // expense exists to navigate to it.
                            if (baseTask == LlmTasks.EXPENSE_SCAN_CLEANUP && newId > 0 &&
                                container.settingsRepository.getSnapshot().autoOpenScannedExpense
                            ) {
                                launchExpensesForEdit(context.applicationContext, newId)
                            }
                        } else if (baseTask == LlmTasks.EXPENSE_SCAN_CLEANUP && retryOfExpenseId != null) {
                            // Retry failed again — a stub row already exists for this id, leave it
                            // as-is (isStub stays true) so it can be retried again later.
                            Logger.w(TAG, "Retry LLM cleanup failed for expense $retryOfExpenseId. Error: ${result.error}")
                            withContext(Dispatchers.Main) {
                                val errorMsg = result.error ?: "Unknown parsing error"
                                Toast.makeText(context, "${container.languageManager.getString("manual_review_required")} ($errorMsg)", Toast.LENGTH_LONG).show()
                            }
                        } else if (baseTask == LlmTasks.EXPENSE_SCAN_CLEANUP && storedImageName != null) {
                            // Recovery flow: LLM failed but we have a physical receipt image.
                            // Create a "stub" record so the user doesn't lose the photo and open it.
                            Logger.w(TAG, "LLM failed for scan, entering recovery mode for $storedImageName. Error: ${result.error}")
                            val id = createStubExpense(container, storedImageName)
                            withContext(Dispatchers.Main) {
                                val errorMsg = result.error ?: "Unknown parsing error"
                                Toast.makeText(context, "${container.languageManager.getString("manual_review_required")} ($errorMsg)", Toast.LENGTH_LONG).show()
                            }
                            launchExpensesForEdit(context.applicationContext, id)
                        } else {
                            Logger.w(TAG, "${result.task} failed and no recovery possible. Error: ${result.error}")
                            withContext(Dispatchers.Main) {
                                val errorMsg = result.error ?: container.languageManager.getString("scan_save_failed")
                                Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
                            }
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }

            LlmTasks.CATEGORY_DEDUPLICATION -> {
                val rawJson = result.rawJson
                val mapping = if (result.status == VoxLlmResult.STATUS_SUCCESS && rawJson != null) {
                    CategoryMergeMappingParser.parse(rawJson)
                } else {
                    null
                }
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        if (requestId != null) container.pendingLlmRequestQueue.markFulfilled(requestId)
                        if (mapping != null) container.pendingCategoryMergeRepository.setPendingMapping(mapping)
                    } finally {
                        pending.finish()
                    }
                }
            }

            LlmTasks.EXPENSE_DEDUPLICATION -> {
                val rawJson = result.rawJson
                val groups = if (result.status == VoxLlmResult.STATUS_SUCCESS && rawJson != null) {
                    ExpenseDeduplicationResultParser.parse(rawJson)
                } else {
                    null
                }
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        if (requestId != null) container.pendingLlmRequestQueue.markFulfilled(requestId)
                        if (groups != null) {
                            val expenses = container.expensesRepository.expenses.first()
                            val validated = validateDuplicateGroups(groups, expenses)
                            if (validated.isNotEmpty()) {
                                val isInsertScoped = taskParts.getOrNull(1) == "INSERT_SCOPED"
                                val settings = container.settingsRepository.getSnapshot()
                                if (isInsertScoped && settings.autoAcceptDuplicateMerges) {
                                    container.expensesRepository.applyExpenseDeduplication(validated)
                                } else {
                                    container.expenseDeduplicationRepository.mergePendingGroups(validated)
                                }
                            }
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }

            LlmTasks.NOTIFICATION_EXPENSE_PARSE -> {
                // The notification's key rides along base64-encoded as the task's second segment
                // (see PaymentNotificationListenerService) — decoded back here so the "processed"
                // mark only lands once Commander's reply actually arrives, not at dispatch time.
                // Marked unconditionally below regardless of outcome: a genuine reply (success or
                // failure) means this notification is done being retried, matching how
                // EXPENSE_PARSE/EXPENSE_SCAN_CLEANUP already only surface a failure toast rather than
                // auto-retrying either. If Commander never replies at all (dropped broadcast, crash
                // before it could respond), the key stays unmarked and onListenerConnected()/
                // onNotificationRemoved()/the manual "force-check" button can genuinely retry it later.
                val notificationKey = taskParts.getOrNull(1)?.let {
                    try { String(android.util.Base64.decode(it, android.util.Base64.NO_WRAP), Charsets.UTF_8) } catch (e: Exception) { null }
                }
                // Deterministic (the user starred this exact source app as a bank), not the LLM's
                // echo of it — the prompt asks the model to repeat the bank name back
                // character-for-character in its JSON reply, which is exactly the kind of instruction
                // an LLM can silently drop or garble. Preferring this over parsed.bank below is what
                // actually fixes an empty "bank" field on an otherwise-successful parse.
                val knownBankName = taskParts.getOrNull(2)?.takeIf { it.isNotEmpty() }?.let {
                    try { String(android.util.Base64.decode(it, android.util.Base64.NO_WRAP), Charsets.UTF_8) } catch (e: Exception) { null }
                }
                val rawJson = result.rawJson
                val parsed = if (result.status == VoxLlmResult.STATUS_SUCCESS && rawJson != null) {
                    NotificationExpenseParseResultParser.parse(rawJson)
                } else {
                    Logger.w(TAG, "Notification expense parse failed: ${result.error}")
                    null
                }

                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        if (requestId != null) container.pendingLlmRequestQueue.markFulfilled(requestId)
                        if (notificationKey != null) {
                            ProcessedNotificationKeysStore(context.applicationContext).markProcessed(notificationKey)
                        }
                        if (parsed == null) return@launch

                        val settings = container.settingsRepository.getSnapshot()
                        // Belt-and-suspenders past the JSON-parse layer's own optCleanString guard —
                        // this is the only guard for fields not sourced from raw JSON.
                        val cleanTitle = FieldCleaner.clean(parsed.title, "title", "NotificationExpense")
                        val cleanVendor = FieldCleaner.clean(parsed.vendor, "vendor", "NotificationExpense")
                        val cleanBank = knownBankName ?: FieldCleaner.clean(parsed.bank, "bank", "NotificationExpense")
                        val cleanCategory = FieldCleaner.clean(parsed.category, "category", "NotificationExpense")
                        if (settings.autoAcceptNotificationExpenses) {
                            // Same insert path as ExpensesStateManager.approveNotificationExpense —
                            // skips the pending-review queue entirely. It's still a normal, editable
                            // expense row afterward, just created without an explicit Approve tap.
                            val newExpenseId = container.expensesRepository.addParsedExpense(
                                title = cleanTitle,
                                totalAmount = parsed.totalAmount,
                                currencyCode = parsed.currency ?: settings.defaultCurrency,
                                vendor = cleanVendor,
                                bank = cleanBank,
                                location = null,
                                comments = null,
                                dateTime = System.currentTimeMillis(),
                                spokenCategory = cleanCategory,
                                defaultCategoryId = settings.defaultVoiceCategoryId,
                                autoCreate = settings.autoCreateVoiceCategory,
                                direction = parsed.direction,
                                nearDuplicateCheckEnabled = settings.duplicateCheckModeAutomatic != ExpensesSettings.MODE_AI,
                                nearDuplicateFuzzyMatch = settings.nearDuplicateFuzzyMatchEnabled,
                                nearDuplicateTimeWindowMillis = TimeUnit.MINUTES.toMillis(settings.nearDuplicateTimeWindowMinutes.toLong()),
                                merchantMemoryEnabled = settings.merchantCategoryMemoryEnabled,
                                merchantMemoryThreshold = settings.merchantCategoryMemoryThreshold
                            )
                            maybeRequestScopedDuplicateCheck(context, container, settings.duplicateCheckModeAutomatic, newExpenseId)
                        } else {
                            container.pendingNotificationExpenseRepository.addPending(
                                PendingNotificationExpense(
                                    id = System.nanoTime(),
                                    title = cleanTitle,
                                    totalAmount = parsed.totalAmount,
                                    currency = parsed.currency ?: settings.defaultCurrency,
                                    vendor = cleanVendor,
                                    category = cleanCategory,
                                    bank = cleanBank,
                                    direction = parsed.direction,
                                    capturedAt = System.currentTimeMillis()
                                )
                            )
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
            else -> {
                Logger.d(TAG, "Ignoring unknown LLM task: ${result.task}")
                // Still a definitive reply even though this task type isn't recognized — clear its
                // queue row so it isn't retried forever for no reason (retrying can't change whether
                // this receiver understands the task).
                if (requestId != null) {
                    val pending = goAsync()
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            container.pendingLlmRequestQueue.markFulfilled(requestId)
                        } finally {
                            pending.finish()
                        }
                    }
                }
            }
        }
    }

    /** Returns the id of the newly-inserted row (or [NEAR_DUPLICATE_MERGED_RESULT]/a negative
     *  failure sentinel — see [com.voxapps.expenses.data.ExpensesRepository.addExpense]'s doc
     *  comment) so callers can decide whether to navigate to it (see the scan-specific auto-open
     *  branch where this is called). */
    private suspend fun createExpenseFromParsed(
        appContext: Context,
        container: ExpensesContainer,
        parsed: ExpenseParseResultParser.Parsed,
        imageName: String?
    ): Long {
        val settings: ExpensesSettings = container.settingsRepository.getSnapshot()
        val items = parsed.items.map {
            ExpenseLineItem(
                expenseId = 0,
                name = it.name,
                quantity = it.quantity,
                unitPrice = it.unitPrice,
                netAmount = it.netAmount,
                vatAmount = it.vatAmount,
                grossAmount = it.grossAmount
            )
        }
        // "location" comes from the LLM only for a scan (a receipt's printed address, if any) — for
        // voice, parsed.location is always null since that prompt never asks for it, so this falls
        // straight through to the GPS-derived city for both tasks uniformly. Deterministic
        // (LLM-read) beats guessed (GPS-derived) whenever both are available, same priority as the
        // notification-capture flow's bank-name handling. The settings toggle governs *every* form
        // of location fill, not just the GPS fallback — turning it off means a receipt's own printed
        // address is left unused too, not silently kept.
        val location = if (!settings.locationPrefillEnabled) {
            null
        } else {
            parsed.location?.let { FieldCleaner.clean(it, "location", "Expense") }
                ?: ExpensesLocationHelper.resolveCurrentCity(appContext)
        }
        // Belt-and-suspenders past the JSON-parse layer's own optCleanString guard — this is the
        // only guard for fields not sourced from raw JSON.
        val newExpenseId = container.expensesRepository.addParsedExpense(
            title = FieldCleaner.clean(parsed.title, "title", "Expense"),
            totalAmount = parsed.totalAmount,
            currencyCode = parsed.currency ?: settings.defaultCurrency,
            vendor = FieldCleaner.clean(parsed.vendor, "vendor", "Expense"),
            bank = FieldCleaner.clean(parsed.bank, "bank", "Expense"),
            location = location,
            comments = null,
            dateTime = mergeDateTime(parsed.date, parsed.time),
            spokenCategory = FieldCleaner.clean(parsed.category, "category", "Expense"),
            defaultCategoryId = settings.defaultVoiceCategoryId,
            autoCreate = settings.autoCreateVoiceCategory,
            items = items,
            imageName = imageName,
            direction = parsed.direction,
            nearDuplicateCheckEnabled = settings.duplicateCheckModeAutomatic != ExpensesSettings.MODE_AI,
            nearDuplicateFuzzyMatch = settings.nearDuplicateFuzzyMatchEnabled,
            nearDuplicateTimeWindowMillis = TimeUnit.MINUTES.toMillis(settings.nearDuplicateTimeWindowMinutes.toLong()),
            merchantMemoryEnabled = settings.merchantCategoryMemoryEnabled,
            merchantMemoryThreshold = settings.merchantCategoryMemoryThreshold
        )

        maybeRequestScopedDuplicateCheck(appContext, container, settings.duplicateCheckModeAutomatic, newExpenseId)

        if (newExpenseId > 0 && parsed.itemsSumMismatch) {
            Logger.w(
                TAG,
                "Items sum mismatch for expense $newExpenseId: totalAmount=${parsed.totalAmount} " +
                    "itemsSum=${parsed.items.sumOf { it.quantity * it.unitPrice }}"
            )
        }

        if (newExpenseId > 0 && settings.voiceSaveToastEnabled) {
            val label = parsed.title?.takeIf { it.isNotBlank() } ?: parsed.vendor ?: parsed.totalAmount.toString()
            val template = container.languageManager.getString("toast_expense_saved")
            val msg = if (template.contains("%1\$s") || template.contains("%s")) {
                try { String.format(template, label) } catch (e: Exception) { "$template $label" }
            } else {
                "$template: $label"
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(appContext, msg, Toast.LENGTH_SHORT).show()
            }
        } else if (newExpenseId <= 0 && newExpenseId != NEAR_DUPLICATE_MERGED_RESULT) {
            Logger.e(TAG, "Failed to save parsed expense to database. ID: $newExpenseId")
            withContext(Dispatchers.Main) {
                Toast.makeText(appContext, container.languageManager.getString("scan_save_failed"), Toast.LENGTH_LONG).show()
            }
        } else if (newExpenseId == NEAR_DUPLICATE_MERGED_RESULT) {
            Logger.d(TAG, "Parsed expense merged into an existing near-duplicate instead of inserting")
        }

        return newExpenseId
    }

    private suspend fun updateExpenseFromRetry(
        appContext: Context,
        container: ExpensesContainer,
        parsed: ExpenseParseResultParser.Parsed,
        expenseId: Long
    ) {
        val existing = container.expensesRepository.getExpenseById(expenseId)
        if (existing == null) {
            Logger.e(TAG, "Retry target expense $expenseId no longer exists")
            return
        }
        val settings: ExpensesSettings = container.settingsRepository.getSnapshot()
        val items = parsed.items.map {
            ExpenseLineItem(
                expenseId = expenseId,
                name = it.name,
                quantity = it.quantity,
                unitPrice = it.unitPrice,
                netAmount = it.netAmount,
                vatAmount = it.vatAmount,
                grossAmount = it.grossAmount
            )
        }
        val updated = existing.expense.copy(
            title = parsed.title?.trim()?.takeIf { it.isNotEmpty() } ?: existing.expense.title,
            totalAmount = parsed.totalAmount,
            currencyCode = parsed.currency ?: settings.defaultCurrency,
            vendor = parsed.vendor,
            bank = parsed.bank,
            // Same priority (and same toggle-respects-everything rule) as the first-attempt path:
            // LLM-read beats GPS-derived, and the whole thing is skipped if the user turned location
            // prefill off. A retry can land long after the original scan, so this is *current*
            // location, not the location at scan time — the best available substitute when nothing
            // better was ever captured.
            location = if (!settings.locationPrefillEnabled) {
                existing.expense.location
            } else {
                parsed.location ?: ExpensesLocationHelper.resolveCurrentCity(appContext)
            },
            dateTime = mergeDateTime(parsed.date, parsed.time),
            comments = null,
            isStub = false
        )
        container.expensesRepository.updateExpense(updated, items)

        if (parsed.itemsSumMismatch) {
            Logger.w(
                TAG,
                "Items sum mismatch for retried expense $expenseId: totalAmount=${parsed.totalAmount} " +
                    "itemsSum=${parsed.items.sumOf { it.quantity * it.unitPrice }}"
            )
        }

        withContext(Dispatchers.Main) {
            Toast.makeText(appContext, container.languageManager.getString("toast_expense_saved"), Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun createStubExpense(
        container: ExpensesContainer,
        imageName: String
    ): Long {
        val settings = container.settingsRepository.getSnapshot()
        // Stub: 0.0 amount is valid but needs manual entry.
        return container.expensesRepository.addExpense(
            title = container.languageManager.getString("manual_review_required"),
            totalAmount = 0.0,
            currencyCode = settings.defaultCurrency,
            vendor = null,
            bank = null,
            location = null,
            dateTime = System.currentTimeMillis(),
            comments = "LLM parsing failed for this scan.",
            categoryId = null,
            imageName = imageName,
            isStub = true
        )
    }

    private fun launchExpensesForEdit(context: Context, expenseId: Long) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(VoxIpc.EXTRA_EXPENSE_ID, expenseId)
        }
        if (intent != null) {
            context.startActivity(intent)
        }
    }

    private fun mergeDateTime(dateStr: String?, timeStr: String?): Long {
        val now = LocalDateTime.now()
        
        val date = try {
            if (dateStr != null) {
                val d = LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE)
                // Hard validation: no future dates
                if (d.isAfter(now.toLocalDate())) now.toLocalDate() else d
            } else now.toLocalDate()
        } catch (e: Exception) {
            now.toLocalDate()
        }
        
        val time = try {
            if (timeStr != null) {
                val t = LocalTime.parse(timeStr, DateTimeFormatter.ofPattern("HH:mm"))
                // Hard validation: if today, no future time
                if (date == now.toLocalDate() && t.isAfter(now.toLocalTime())) now.toLocalTime() else t
            } else now.toLocalTime()
        } catch (e: Exception) {
            now.toLocalTime()
        }

        return LocalDateTime.of(date, time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    /** Fires an async, scoped AI duplicate check for a freshly-inserted row when the automatic mode
     *  includes AI ([ExpensesSettings.MODE_LOCAL_AND_AI]/[ExpensesSettings.MODE_AI]) — scoped to just
     *  the new row's own same-amount candidate cluster (see
     *  [com.voxapps.expenses.data.ExpensesRepository.duplicateCandidateClusters]), not the whole
     *  expense list, mirrors [com.voxapps.expenses.state.ExpensesStateManager]'s identical helper for
     *  the manual-entry path. Never auto-applies — any result lands in the normal review list. */
    private fun maybeRequestScopedDuplicateCheck(
        context: Context,
        container: ExpensesContainer,
        automaticMode: String,
        newExpenseId: Long
    ) {
        if (newExpenseId <= 0 || automaticMode == ExpensesSettings.MODE_LOCAL) return
        CoroutineScope(Dispatchers.IO).launch {
            val candidates = container.expensesRepository.duplicateCandidateClusters(scopedToId = newExpenseId).flatten()
            if (candidates.size < 2) return@launch
            val summaries = candidates.map {
                ExpenseSummary(it.id, it.title, it.vendor, it.totalAmount, it.currencyCode, it.dateTime)
            }
            ExpenseDeduplicationRequestSender.send(context, container.pendingLlmRequestQueue, summaries, scoped = true)
        }
    }

    /** The AI's proposed groups are never trusted blindly — a hallucinated group (claiming two
     *  expenses are duplicates when they share nothing in common) is dropped here before it ever
     *  reaches the review list, by re-checking each duplicate id against the same hard fields
     *  [com.voxapps.expenses.data.ExpenseNearDuplicateDetector] requires. A genuine duplicate
     *  trivially survives this (it shares an amount+currency with [DuplicateGroup.keepId] by
     *  definition); a fabricated one doesn't. A group left with no surviving duplicates is dropped
     *  entirely rather than staged empty. */
    private fun validateDuplicateGroups(groups: List<DuplicateGroup>, expenses: List<Expense>): List<DuplicateGroup> {
        val byId = expenses.associateBy { it.id }
        return groups.mapNotNull { group ->
            val keep = byId[group.keepId] ?: return@mapNotNull null
            val validDuplicateIds = group.duplicateIds.filter { id ->
                val duplicate = byId[id]
                duplicate != null && duplicate.totalAmount == keep.totalAmount && duplicate.currencyCode == keep.currencyCode
            }
            if (validDuplicateIds.isEmpty()) null else DuplicateGroup(group.keepId, validDuplicateIds)
        }
    }
}
