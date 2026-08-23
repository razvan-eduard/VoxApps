package com.voxapps.expenses.receiver

import com.voxapps.docread.InvoiceTotalsReconciler
import com.voxapps.docread.TableItemsPreParse
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.voxapps.attachments.AttachmentEntity
import com.voxapps.attachments.AttachmentSource
import com.voxapps.expenses.ExpensesApplication
import com.voxapps.expenses.data.Expense
import com.voxapps.expenses.data.ExpenseLineItem
import com.voxapps.expenses.data.ExpenseSource
import com.voxapps.expenses.data.ExpensesAttachments
import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.expenses.data.ALREADY_PRESENT_RESULT
import com.voxapps.expenses.data.RECOGNIZED_NOT_INSERTED
import com.voxapps.expenses.data.NEAR_DUPLICATE_MERGED_RESULT
import com.voxapps.expenses.data.NearDuplicateConfig
import com.voxapps.expenses.data.ExpenseSuggestionTarget
import com.voxapps.expenses.data.PendingLineItemsJson
import com.voxapps.expenses.data.ExpenseWithDetails
import com.voxapps.expenses.data.toNearDuplicateConfig
import com.voxapps.expenses.di.ExpensesContainer
import com.voxapps.expenses.domain.llm.CategoryMergeMappingParser
import com.voxapps.expenses.domain.llm.DuplicateGroup
import com.voxapps.expenses.domain.llm.ExpenseDeduplicationRequestSender
import com.voxapps.expenses.domain.llm.ExpenseDeduplicationResultParser
import com.voxapps.expenses.domain.llm.ExpenseSummary
import com.voxapps.expenses.domain.llm.ExpenseParseResultParser
import com.voxapps.expenses.domain.llm.ExpenseAmountMismatch
import com.voxapps.expenses.domain.llm.ScanPreParse
import com.voxapps.expenses.domain.location.resolveCurrentCityName
import com.voxapps.recordflow.RecordFlow
import com.voxapps.expenses.domain.llm.ExpenseScanFlow
import com.voxapps.expenses.domain.llm.ExpenseVoiceFlow
import com.voxapps.expenses.domain.llm.LlmTasks
import com.voxapps.expenses.domain.llm.NotificationExpenseParseResultParser
import com.voxapps.expenses.domain.llm.PendingNotificationExpense
import com.voxapps.expenses.domain.llm.PendingNotificationExpenseRepository
import com.voxapps.expenses.ui.widget.ExpensesWidget
import com.voxapps.datahygiene.FieldCleaner
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxLlmRequestQueue
import com.voxapps.ipc.VoxLlmResult
import com.voxapps.logging.Logger
import androidx.glance.appwidget.updateAll
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
        // retry, "TASK:IMAGE_NAME:RETRY_OF_EXPENSE_ID") — or, for a pending Scan capture (see
        // ExpenseScanCleanupRequestSender.sendPendingCreate), "TASK:pending:GROUP_ID:FILE_NAMES",
        // a distinct shape carrying no imageName/retryOfExpenseId at all since neither concept
        // applies (there's no legacy receipt field to set, and nothing to retry yet).
        val taskParts = task.split(":")
        val baseTask = taskParts[0]
        val isPendingScanCreate = baseTask == LlmTasks.EXPENSE_SCAN_CLEANUP && taskParts.getOrNull(1) == "pending"
        val storedImageName = if (isPendingScanCreate) null else taskParts.getOrNull(1)
        val retryOfExpenseId = if (isPendingScanCreate) null else taskParts.getOrNull(2)?.toLongOrNull()
        val pendingGroupId = if (isPendingScanCreate) taskParts.getOrNull(2)?.takeIf { it.isNotEmpty() } else null
        val pendingFileNames = if (isPendingScanCreate) taskParts.getOrNull(3)?.split(",")?.filter { it.isNotBlank() } ?: emptyList() else emptyList()

        Logger.d(TAG, "LLM result: status=${result.status} task=${result.task} baseTask=$baseTask imageName=$storedImageName")
        val rawJson = result.rawJson
        if (rawJson != null) {
            Logger.d(TAG, "LLM rawJson length: ${rawJson.length}")
        }

        when (baseTask) {
            LlmTasks.EXPENSE_PARSE, LlmTasks.EXPENSE_SCAN_CLEANUP -> {
                val rawJson = result.rawJson
                val isSuccess = result.status == VoxLlmResult.STATUS_SUCCESS && rawJson != null

                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        // Whatever this scan's text yielded deterministically before it was sent.
                        // Read first, because the reply is only complete together with it: fields
                        // covered here were suppressed in the prompt, so their absence from the
                        // reply is by design rather than a failure to find them.
                        val preParse = container.scanPreParseRepository.take(requestId)
                        // The utterance this reply is about, from whichever side still has it: the
                        // reply itself when Commander composed the request from a cached template,
                        // and this app's own queue when it composed the request. Read before
                        // markFulfilled below, which deletes the row that holds it.
                        val spokenInput = result.input?.takeIf { it.isNotBlank() }
                            ?: container.pendingLlmRequestQueue.originalInput(requestId)
                        val parsed = if (isSuccess) {
                            ExpenseParseResultParser
                                .parse(rawJson!!, requireTotalAmount = preParse?.total == null)
                                ?.withPreParse(preParse)
                                ?.withoutDisprovedItems(preParse)
                        } else null
                        if (requestId != null) container.pendingLlmRequestQueue.markFulfilled(requestId)
                        if (parsed != null && retryOfExpenseId != null) {
                            // Retry succeeded: update the existing stub row in place rather than
                            // inserting a new one (which would duplicate the expense and orphan
                            // the original stub).
                            updateExpenseFromRetry(context.applicationContext, container, parsed, retryOfExpenseId)
                        } else if (parsed != null) {
                            // Through the flow rather than straight to the writer: the rung decides
                            // how much of this answer lands on the record and how much is offered on
                            // it instead, and only the flow knows that. Which flow differs; that
                            // they both end here does not.
                            val newId = if (baseTask == LlmTasks.EXPENSE_SCAN_CLEANUP) {
                                val settings = container.settingsRepository.getSnapshot()
                                val outcome = RecordFlow.deliver(
                                    spec = ExpenseScanFlow(
                                        context.applicationContext, container, storedImageName, preParse
                                    ),
                                    reading = null,
                                    level = ExpensesSettings.scanLevelOf(settings.scanModelUse),
                                    reply = rawJson!!
                                )
                                (outcome as? RecordFlow.Outcome.Committed)?.recordId ?: 0L
                            } else {
                                val spec = ExpenseVoiceFlow(context.applicationContext, container)
                                val outcome = RecordFlow.deliver(
                                    spec = spec,
                                    // Re-read rather than carried: what a rule settles from a
                                    // sentence is the same on both sides of the round trip, so the
                                    // sentence is what has to survive it, not the reading.
                                    reading = spokenInput?.let { spec.read(it) },
                                    level = ExpensesSettings.VOICE_FLOW_SUPPORT.default,
                                    reply = rawJson!!
                                )
                                (outcome as? RecordFlow.Outcome.Committed)?.recordId ?: 0L
                            }
                            if (isPendingScanCreate && newId > 0) {
                                linkPendingScanAttachments(container, newId, pendingFileNames, pendingGroupId)
                            }
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
                            val id = createStubExpense(container, storedImageName, preParse)
                            withContext(Dispatchers.Main) {
                                val errorMsg = result.error ?: "Unknown parsing error"
                                Toast.makeText(context, "${container.languageManager.getString("manual_review_required")} ($errorMsg)", Toast.LENGTH_LONG).show()
                            }
                            launchExpensesForEdit(context.applicationContext, id)
                        } else if (isPendingScanCreate && pendingFileNames.isNotEmpty()) {
                            // Same recovery flow as the legacy imageName case above, generalized to
                            // N pages: LLM failed but every page is already staged as a plain
                            // attachment — create a stub so the user doesn't lose them, and link them
                            // once the id is known (mirrors the success path's linking, just off the
                            // failure branch instead).
                            Logger.w(TAG, "LLM failed for pending scan, entering recovery mode for ${pendingFileNames.size} page(s). Error: ${result.error}")
                            val id = createStubExpense(container, imageName = null, preParse = preParse)
                            linkPendingScanAttachments(container, id, pendingFileNames, pendingGroupId)
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
                        // Without this, the home-screen widget's refresh depends entirely on
                        // ExpensesContainer's independent reactive collector noticing the DB write
                        // and finishing its own async updateAll() — which isn't tied to this
                        // receiver's goAsync() window at all, so the process is free to be reclaimed
                        // the instant pending.finish() returns, racing ahead of that redraw. Awaiting
                        // it here, inside the same wake window that made the write, closes that race.
                        ExpensesWidget().updateAll(context.applicationContext)
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
                                val taskSegments = task.split(":")
                                val isInsertScoped = taskSegments.contains("INSERT_SCOPED")
                                val isBatchAutoApply = taskSegments.contains("BATCH_AUTO_APPLY")
                                val settings = container.settingsRepository.getSnapshot()

                                if ((isInsertScoped && settings.autoAcceptDuplicateMerges) || isBatchAutoApply) {
                                    container.expensesRepository.applyExpenseDeduplication(validated)
                                    // See the EXPENSE_PARSE branch's comment above — same
                                    // goAsync()/process-death race for the widget refresh.
                                    ExpensesWidget().updateAll(context.applicationContext)
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
                val isParseSuccess = result.status == VoxLlmResult.STATUS_SUCCESS && rawJson != null
                if (!isParseSuccess) Logger.w(TAG, "Notification expense parse failed: ${result.error}")
                // A STATUS_ERROR reply (OpenAI/network/etc. failure on Commander's side — see
                // OpenAiInterpreter.lastErrorReason for the specific cause carried in result.error)
                // is transient, unlike a STATUS_SUCCESS reply this receiver simply doesn't recognize
                // as a payment (parsed == null there is a *correct*, final "not a payment" outcome).
                // Only mark this request/notification handled on a genuine reply — leaving both
                // markers untouched on an error means the queue's own 15-minute retry cycle
                // (PendingLlmRequestRetryWorker) naturally re-sends this exact request once the
                // outage clears, and the notification stays eligible for the listener's normal
                // catch-up paths too — without this, an API outage silently and permanently dropped
                // whatever was captured during it the moment Commander's error reply arrived,
                // regardless of whether the user ever dismissed the source notification.
                val isRetryableFailure = result.status != VoxLlmResult.STATUS_SUCCESS

                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        if (isRetryableFailure) return@launch
                        // Before the row is cleared, because clearing it is what tells a later reply
                        // that this capture is already answered. One request can come back twice —
                        // the queue re-sends a stored row rather than multiplying it — and only the
                        // first reply means anything. Without this the second one reaches the insert
                        // and is refused there by the unique uid: correct, but it reads to the user
                        // as their payment having failed to save.
                        if (!container.pendingLlmRequestQueue.isPending(requestId)) {
                            Logger.d(TAG, "A second reply for a capture already answered — ignored")
                            return@launch
                        }
                        if (requestId != null) container.pendingLlmRequestQueue.markFulfilled(requestId)
                        if (notificationKey != null) {
                            ProcessedNotificationKeysStore(context.applicationContext).markProcessed(notificationKey)
                        }
                        // Reunite the reply with what was resolved before it was asked —
                        // suppressed fields are absent from the JSON by design, and the
                        // deterministic value outranks anything the model produced anyway: it was
                        // read from the notification's own characters. In here rather than at the
                        // branch top because the store read is a suspend, and onReceive's
                        // synchronous part must stay off IO.
                        val preParse = if (isParseSuccess) container.scanPreParseRepository.take(requestId) else null
                        if (!isParseSuccess) return@launch

                        // The rest is the flow's: it reads the answer against what was suppressed,
                        // and either files the expense or leaves it in the review queue. What stays
                        // here is about the request rather than the record.
                        val flow = com.voxapps.expenses.domain.llm.NotificationExpenseFlow(
                            context.applicationContext, container, preParse, knownBankName
                        )
                        val settings = container.settingsRepository.getSnapshot()
                        RecordFlow.deliver(
                            spec = flow,
                            reading = null,
                            level = ExpensesSettings.notificationLevelOf(settings.notificationModelUse),
                            reply = rawJson!!
                        )
                        // Asked for last, and only for a capture that landed somewhere. The flow is
                        // asked what it kept rather than the outcome: a commit that queued for review
                        // returns null, which from out here is indistinguishable from having kept
                        // nothing — and the difference decides whether a message you have not seen
                        // disappears.
                        if (
                            notificationKey != null &&
                            settings.dismissNotificationOnCapture &&
                            flow.kept != com.voxapps.expenses.domain.llm.NotificationExpenseFlow.Kept.NOTHING
                        ) {
                            PaymentNotificationListenerService.dismissCaptured(notificationKey)
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }

            LlmTasks.EXPENSE_LINEITEMS_RESCAN -> {
                // A photo attached to an already-saved expense after the fact — see
                // ExpenseScanCleanupRequestSender.sendLineItemsRescan's doc comment. Nothing here is
                // applied directly to the Expense/line-item tables — everything, items included, is
                // staged as a proposal for ExpenseEditScreen to show as a tappable chip or banner
                // (see :core:suggestions). An edit screen already open holds its line items as a
                // local snapshot and never observes the database, so a direct write would land
                // where nobody is looking; the suggestion row it does observe reaches it either
                // way, and items become reviewable like every other field.
                val expenseId = taskParts.getOrNull(1)?.toLongOrNull()
                val sourceGroupId = taskParts.getOrNull(2)?.takeIf { it.isNotEmpty() }
                val rawJson = result.rawJson
                // requireTotalAmount=false: a photo with no clearly printed total shouldn't discard
                // genuinely-found line items (or other fields) along with it.
                val rawParsed = if (result.status == VoxLlmResult.STATUS_SUCCESS && rawJson != null) {
                    ExpenseParseResultParser.parse(rawJson, requireTotalAmount = false)
                } else {
                    Logger.w(TAG, "Line-items rescan failed: ${result.error}")
                    null
                }
                if (rawParsed == null && rawJson != null) {
                    Logger.w(TAG, "Line-items rescan: reply didn't parse as valid JSON: $rawJson")
                } else if (rawJson != null) {
                    // Full reply, not just the parsed item count — lets a mismatch between what the
                    // model returned and what ended up in `parsed.items` be told apart from the model
                    // itself only finding some of the receipt's items in the first place.
                    Logger.d(TAG, "Line-items rescan raw reply: $rawJson")
                }

                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        if (requestId != null) container.pendingLlmRequestQueue.markFulfilled(requestId)
                        // The deterministic pre-parse reunites with the reply here, off the main
                        // thread — the suggestion should carry the printed total and date even
                        // when the model was told not to look for them.
                        val parsed = rawParsed?.withPreParse(container.scanPreParseRepository.take(requestId))
                        val existing = expenseId?.let { container.expensesRepository.getExpenseById(it) }
                        if (existing == null) {
                            Logger.w(TAG, "Line-items rescan target expense $expenseId no longer exists")
                        } else if (parsed != null) {
                            Logger.d(TAG, "Line-items rescan for expense $expenseId: parsed ${parsed.items.size} item(s)")
                            parsed.items.forEach {
                                Logger.d(TAG, "  item: name=\"${it.name}\" qty=${it.quantity} unitPrice=${it.unitPrice} net=${it.netAmount} vat=${it.vatAmount} gross=${it.grossAmount}")
                            }
                        }
                        var didSomething = false
                        if (parsed != null && existing != null) {
                            val offered = buildFieldSuggestion(parsed, existing)
                            if (offered.isNotEmpty()) {
                                container.suggestionStore.offer(existing.expense.id, offered, sourceGroupId)
                                didSomething = true
                            }
                        }
                        val toastKey = if (didSomething) "toast_lineitems_rescanned" else "toast_lineitems_rescan_empty"
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, container.languageManager.getString(toastKey), Toast.LENGTH_SHORT).show()
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
    internal suspend fun createExpenseFromParsed(
        appContext: Context,
        container: ExpensesContainer,
        parsed: ExpenseParseResultParser.Parsed,
        imageName: String?,
        preParse: ScanPreParse? = null
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
                ?: resolveCurrentCityName(appContext, container.settingsRepository)
        }
        // Belt-and-suspenders past the JSON-parse layer's own optCleanString guard — this is the
        // only guard for fields not sourced from raw JSON.
        val localModeActive = settings.duplicateCheckModeAutomatic == ExpensesSettings.MODE_LOCAL ||
            settings.duplicateCheckModeAutomatic == ExpensesSettings.MODE_LOCAL_AND_AI
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
            nearDuplicateCheckEnabled = localModeActive && !settings.automaticProtectionReviewOnly,
            nearDuplicateConfig = settings.toNearDuplicateConfig(),
            correctionsEnabled = settings.fieldCorrectionMemoryEnabled,
            correctionsThreshold = settings.fieldCorrectionThreshold,
            correctionsApplyMode = settings.fieldCorrectionApplyMode,
            // This one helper handles both voice and scan tasks — imageName is only ever non-null for
            // a scan (the receipt photo), never for voice, so it's already the exact signal needed.
            source = if (imageName != null) ExpenseSource.SCAN else ExpenseSource.VOICE,
            previousBalanceAmount = preParse?.previousBalance,
            invoiceOwnAmount = preParse?.invoiceOwnTotal
        )
        stageLocalReviewIfNeeded(container, settings, localModeActive, newExpenseId)

        maybeRequestScopedDuplicateCheck(appContext, container, settings.duplicateCheckModeAutomatic, newExpenseId, settings.toNearDuplicateConfig())

        if (newExpenseId > 0 && parsed.items.isNotEmpty()) {
            // Items sum to the invoice's OWN charges — net, net plus its VAT, or the invoice total
            // as printed — and never to a grand total carrying someone's unpaid history. Matching
            // any of those is not a plausibility check but a proof the rows were read correctly,
            // which is why it is worth stating separately from the soft mismatch warning below.
            val itemsSum = parsed.items.sumOf { it.quantity * it.unitPrice }
            val invoiceOwn = preParse?.invoiceOwnTotal
            val verdict = InvoiceTotalsReconciler.reconcile(
                grandTotal = parsed.totalAmount,
                invoiceTotal = invoiceOwn,
                previousBalance = preParse?.previousBalance
            )
            if (verdict != InvoiceTotalsReconciler.Verdict.UNTESTABLE) {
                Logger.d(TAG, "Invoice totals for expense $newExpenseId: $verdict")
            }
            if (InvoiceTotalsReconciler.itemsBelong(itemsSum, invoiceOwn ?: parsed.totalAmount)) {
                Logger.d(TAG, "Items for expense $newExpenseId sum to a figure the document prints")
            } else if (parsed.itemsSumMismatch) {
                val reference = invoiceOwn ?: parsed.totalAmount
                if (ExpenseAmountMismatch.isGrossMismatch(reference, itemsSum)) {
                    Logger.w(
                        TAG,
                        "Items sum mismatch for expense $newExpenseId: reference=$reference itemsSum=$itemsSum"
                    )
                }
            }
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
        } else if (newExpenseId in RECOGNIZED_NOT_INSERTED) {
            // Every one of these means the record is already there — answered twice, refused as a
            // duplicate, or folded into the one it duplicated. None of them is a failure to save,
            // and saying so while the expense sits in the list is worse than saying nothing: it
            // reads as data lost, and the "try again" it offers would be refused for the same
            // reason it was refused the first time.
            Logger.d(TAG, "Nothing inserted, and nothing wrong: $newExpenseId")
        } else if (newExpenseId <= 0) {
            Logger.e(TAG, "Failed to save parsed expense to database. ID: $newExpenseId")
            withContext(Dispatchers.Main) {
                Toast.makeText(appContext, container.languageManager.getString("scan_save_failed"), Toast.LENGTH_LONG).show()
            }
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
                parsed.location ?: resolveCurrentCityName(appContext, container.settingsRepository)
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

    /**
     * Overlays what regex established onto what the model returned. The total is authoritative
     * here — it was read from the document's own printed digits and the model was told not to
     * produce it — while date and time only fill gaps, since a reply that still carries them was
     * asked for them and had the whole document to weigh.
     */
    /** Also used by [com.voxapps.expenses.domain.llm.ExpenseScanFlow], which parses the same reply
     *  on the flow's behalf — one reunion rule, not two. */
    internal fun ExpenseParseResultParser.Parsed.withPreParse(
        preParse: ScanPreParse?
    ): ExpenseParseResultParser.Parsed {
        if (preParse == null) return this
        // Deterministically-read items (validated against the printed total at pre-parse time)
        // replace whatever the model returned — it was told to return none.
        val preItems = com.voxapps.docread.TableItemsPreParse.fromJson(preParse.itemsJson)
            ?.map { ExpenseParseResultParser.ParsedItem(name = it.name, quantity = it.quantity, unitPrice = it.unitPrice) }
        return copy(
            totalAmount = preParse.total ?: totalAmount,
            date = date ?: preParse.date,
            time = time ?: preParse.time,
            items = preItems ?: items
        )
    }

    /**
     * The same standard for items whoever produced them: a list the document's own arithmetic
     * disproves is dropped rather than saved.
     *
     * The deterministic reader already refuses to emit rows it cannot prove. A model reading the
     * same mangled text has no such discipline — handed OCR debris it will faithfully report items
     * named "a", "b", "c" — and those were being written into records anyway, where a person has to
     * notice they are wrong before they can fix them. An empty item list is the better record.
     *
     * Unprovable is not disproved, and only the second loses its items: when the document printed no
     * figure this can be checked against, the model's reading stands, because there is nothing to
     * contradict it. That keeps ordinary receipts — where the total is often the only number the app
     * can read — working exactly as they do today.
     */
    internal fun ExpenseParseResultParser.Parsed.withoutDisprovedItems(
        preParse: ScanPreParse?
    ): ExpenseParseResultParser.Parsed {
        if (items.isEmpty()) return this
        // Items the deterministic reader proved are already beyond question.
        if (TableItemsPreParse.fromJson(preParse?.itemsJson) != null) return this

        val invoiceOwn = preParse?.invoiceOwnTotal ?: totalAmount
        val targets = InvoiceTotalsReconciler.acceptedTargets(invoiceOwn)
        if (targets.isEmpty()) return this

        val sum = items.sumOf { it.quantity * it.unitPrice }
        if (InvoiceTotalsReconciler.itemsBelong(sum, invoiceOwn)) return this

        Logger.w(
            TAG,
            "Dropping ${items.size} item(s) summing to $sum: the document prints " +
                "${targets.joinToString()} and nothing else, so this list is not a reading of it"
        )
        return copy(items = emptyList())
    }

    private suspend fun createStubExpense(
        container: ExpensesContainer,
        imageName: String?,
        preParse: ScanPreParse? = null
    ): Long {
        val settings = container.settingsRepository.getSnapshot()
        // A record still carries whatever was established without the model: a scan whose
        // structuring failed is exactly when the deterministically-read total is the only amount
        // there is, and a stub left at zero is a record that says nothing was on the document.
        return container.expensesRepository.addExpense(
            title = container.languageManager.getString("manual_review_required"),
            totalAmount = preParse?.total ?: 0.0,
            currencyCode = settings.defaultCurrency,
            vendor = null,
            bank = null,
            location = null,
            dateTime = mergeDateTime(preParse?.date, preParse?.time),
            comments = "LLM parsing failed for this scan.",
            categoryId = null,
            imageName = imageName,
            isStub = true,
            source = ExpenseSource.SCAN
        )
    }

    /** Links a pending Scan capture's already-staged pages (see [com.voxapps.expenses.receiver.OcrResultReceiver]'s
     *  pending-scan branches) as ordinary AttachmentEntity rows against [expenseId] now that its id is
     *  known — win or lose (see both call sites above): a failed parse still gets its photo(s) linked
     *  to the resulting stub, matching the legacy imageName-based recovery flow's own "never lose the
     *  photo" behavior, just generalized from one field to N ordinary attachments. */
    private suspend fun linkPendingScanAttachments(
        container: ExpensesContainer,
        expenseId: Long,
        fileNames: List<String>,
        groupId: String?
    ) {
        fileNames.forEachIndexed { index, fileName ->
            container.attachmentDao.insert(
                AttachmentEntity(
                    recordType = ExpensesAttachments.RECORD_TYPE,
                    recordId = expenseId,
                    fileName = fileName,
                    source = AttachmentSource.MANUAL,
                    createdAt = System.currentTimeMillis(),
                    groupId = groupId,
                    groupOrder = index
                )
            )
        }
    }

    /** Diffs [parsed] against [existing]'s current field values, returning the fields worth offering
     *  with only the fields that genuinely differ (or fill a current blank) set — never null,
     *  never-blank, never-unchanged fields stay null so ExpenseEditScreen only renders a chip where
     *  there's an actual difference to offer. Returns null (nothing to persist) if every field
     *  already matches. Category is compared by name (the record's current category, if any) since
     *  [ExpenseParseResultParser.Parsed.category] is a raw name, not an id. */
    private fun buildFieldSuggestion(parsed: ExpenseParseResultParser.Parsed, existing: ExpenseWithDetails): Map<String, String?> {
        val expense = existing.expense
        fun String?.diffOrNull(current: String?): String? {
            val clean = this?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return clean.takeIf { !it.equals(current?.trim(), ignoreCase = false) }
        }
        val title = parsed.title.diffOrNull(expense.title)
        val vendor = parsed.vendor.diffOrNull(expense.vendor)
        val bank = parsed.bank.diffOrNull(expense.bank)
        val location = parsed.location.diffOrNull(expense.location)
        val currencyCode = parsed.currency?.uppercase()?.diffOrNull(expense.currencyCode)
        val category = parsed.category.diffOrNull(existing.category?.name)
        val totalAmount = if (!parsed.totalAmount.isNaN() && parsed.totalAmount != expense.totalAmount) {
            parsed.totalAmount
        } else null
        val dateTime = if (parsed.date != null || parsed.time != null) {
            mergeDateTime(parsed.date, parsed.time).takeIf { it != expense.dateTime }
        } else null
        // No meaningful per-item diff against whatever's currently in the draft (unlike the scalar
        // fields above) — the full parsed list, offered as one apply-all-or-nothing suggestion.
        val itemsJson = PendingLineItemsJson.encode(parsed.items)

        // Only what actually differs, keyed by field. An empty map is a rescan that found nothing
        // new — the caller says so rather than storing a row nobody would be shown.
        return mapOf(
            ExpenseSuggestionTarget.KEY_TITLE to title,
            ExpenseSuggestionTarget.KEY_VENDOR to vendor,
            ExpenseSuggestionTarget.KEY_BANK to bank,
            ExpenseSuggestionTarget.KEY_AMOUNT to totalAmount?.toString(),
            ExpenseSuggestionTarget.KEY_CURRENCY to currencyCode,
            ExpenseSuggestionTarget.KEY_CATEGORY to category,
            ExpenseSuggestionTarget.KEY_LOCATION to location,
            ExpenseSuggestionTarget.KEY_DATE_TIME to dateTime?.toString(),
            ExpenseSuggestionTarget.KEY_ITEMS to PendingLineItemsJson.encode(parsed.items)
        ).filterValues { it != null }
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
     *  the new row's own candidate cluster, not the whole expense list, mirrors
     *  [com.voxapps.expenses.state.ExpensesStateManager]'s identical helper for the manual-entry path.
     *  [MODE_LOCAL_AND_AI] recalls that cluster via the configured duplicate rules (automatic-only, see
     *  [com.voxapps.expenses.data.ExpensesRepository.ruleBasedCandidateClusters]); pure [MODE_AI] has no
     *  local component and recalls via a fixed amount/currency/direction match instead (see
     *  [com.voxapps.expenses.data.ExpensesRepository.duplicateCandidateClusters]), never consulting the
     *  rules at all. Never auto-applies — any result lands in the normal review list. */
    private fun maybeRequestScopedDuplicateCheck(
        context: Context,
        container: ExpensesContainer,
        automaticMode: String,
        newExpenseId: Long,
        nearDuplicateConfig: NearDuplicateConfig
    ) {
        val hasAiComponent = automaticMode == ExpensesSettings.MODE_LOCAL_AND_AI || automaticMode == ExpensesSettings.MODE_AI
        if (newExpenseId <= 0 || !hasAiComponent) return
        CoroutineScope(Dispatchers.IO).launch {
            val candidates = if (automaticMode == ExpensesSettings.MODE_LOCAL_AND_AI) {
                container.expensesRepository.ruleBasedCandidateClusters(nearDuplicateConfig, automaticOnly = true, scopedToId = newExpenseId).flatten()
            } else {
                container.expensesRepository.duplicateCandidateClusters(scopedToId = newExpenseId).flatten()
            }
            if (candidates.size < 2) return@launch
            val summaries = candidates.map {
                ExpenseSummary(it.id, it.title, it.vendor, it.totalAmount, it.currencyCode, it.dateTime, it.direction)
            }
            ExpenseDeduplicationRequestSender.send(context, container.pendingLlmRequestQueue, summaries, scoped = true)
        }
    }

    /** Local-rule-engine counterpart of [maybeRequestScopedDuplicateCheck] for
     *  [ExpensesSettings.automaticProtectionReviewOnly] mode — see
     *  [com.voxapps.expenses.state.ExpensesStateManager]'s identical helper for why this runs
     *  *after* a normal insert rather than checking-then-skipping the insert. */
    private suspend fun stageLocalReviewIfNeeded(
        container: ExpensesContainer,
        settings: com.voxapps.expenses.data.preferences.ExpensesSettings,
        localModeActive: Boolean,
        newExpenseId: Long
    ) {
        if (!localModeActive || !settings.automaticProtectionReviewOnly || newExpenseId <= 0) return
        val group = container.expensesRepository.findLocalDuplicateGroupForRow(newExpenseId, settings.toNearDuplicateConfig())
        if (group != null) container.expenseDeduplicationRepository.mergePendingGroups(listOf(group))
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
                // direction is checked here too, not just amount/currency — an incoming top-up/refund
                // and an outgoing payment of the same amount are two different real transactions, never
                // a duplicate, and the AI's proposed groups are never trusted blindly regardless.
                duplicate != null && duplicate.totalAmount == keep.totalAmount &&
                    duplicate.currencyCode == keep.currencyCode && duplicate.direction == keep.direction
            }
            if (validDuplicateIds.isEmpty()) null else DuplicateGroup(group.keepId, validDuplicateIds)
        }
    }
}
