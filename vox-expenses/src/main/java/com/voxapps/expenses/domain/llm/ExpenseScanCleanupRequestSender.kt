package com.voxapps.expenses.domain.llm

import android.content.Context
import com.voxapps.expenses.di.ExpensesContainer
import com.voxapps.ipc.VoxAppsDiscovery.COMMANDER_PACKAGE
import com.voxapps.logging.Logger
import kotlinx.coroutines.flow.first

private const val TAG = "ExpenseScanCleanupRequestSender"

/**
 * Builds and fires the generic-LLM-hook request that turns raw receipt OCR text into structured
 * expense fields (see [ExpenseScanCleanupPromptBuilder]). Shared by [com.voxapps.expenses.receiver.OcrResultReceiver]
 * (fresh scan) and the stub-expense "Retry cleanup" action (re-sends the already-staged raw text
 * without physically rescanning) — [retryOfExpenseId], when set, is embedded in the task metadata so
 * [com.voxapps.expenses.receiver.LlmResultReceiver] updates the existing stub row in place instead of
 * inserting a new one.
 */
object ExpenseScanCleanupRequestSender {
    /**
     * [attachmentUri] optionally attaches the staged receipt image alongside the OCR text — only
     * meaningful when the caller already confirmed Commander's configured engine is multimodal (see
     * [com.voxapps.ipc.VoxCapabilityClient.isMultimodal]) and has granted Commander read access to it
     * (the caller's job; see [com.voxapps.expenses.receiver.OcrResultReceiver]). OCR always runs and
     * is always sent regardless — this is additive, not a replacement (see the collapsed
     * voice-command plan's multimodal section for why skipping OCR isn't done here).
     */
    suspend fun send(
        context: Context,
        container: ExpensesContainer,
        rawText: String,
        imageName: String?,
        retryOfExpenseId: Long? = null,
        attachmentUri: String? = null
    ) {
        val existingCategories = container.expensesRepository.categories.first().map { it.name }
        val settings = container.settingsRepository.getSnapshot()

        // Prompts and regex parsers see the PLAIN reading-order text — the appended table
        // reconstruction (see TableItemsPreParse.TABLE_SECTION_MARKER) exists only for the
        // deterministic items gate, so a reconstruction misfire can never degrade the LLM's input.
        val plainText = TableItemsPreParse.plainText(rawText)
        val preParsed = DateTimeRegexParser.parse(plainText)
        val totals = ReceiptTotalRegexParser.parse(plainText)
        val preParsedTotal = totals.total
        val preParsedItems = TableItemsPreParse.parse(rawText, totals.invoiceTotal ?: totals.total)

        val taskWithMeta = when {
            imageName != null && retryOfExpenseId != null -> "${LlmTasks.EXPENSE_SCAN_CLEANUP}:$imageName:$retryOfExpenseId"
            imageName != null -> "${LlmTasks.EXPENSE_SCAN_CLEANUP}:$imageName"
            else -> LlmTasks.EXPENSE_SCAN_CLEANUP
        }

        // What the configured engine can take decides how much of the prompt is worth sending;
        // an unreachable or older Commander answers no, which is the shape a weak engine can still
        // complete. See VoxCapabilityClient.EngineCapabilities.longPrompt.
        val includeLineItems = com.voxapps.ipc.VoxCapabilityClient.supportsLongPrompt(context)

        val promptText = ExpenseScanCleanupPromptBuilder.build(
            plainText,
            existingCategories,
            settings.defaultCurrency,
            settings.language,
            preParsedDate = preParsed.date,
            preParsedTime = preParsed.time,
            includeLineItems = includeLineItems,
        )

        Logger.d(TAG, "Sending ACTION_LLM_PROCESS to $COMMANDER_PACKAGE for scan cleanup (retryOfExpenseId=$retryOfExpenseId, multimodal=${attachmentUri != null})")
        val requestId = container.pendingLlmRequestQueue.enqueueAndSend(
            context = context,
            sourcePackage = context.packageName,
            task = taskWithMeta,
            promptText = promptText,
            targetPackage = COMMANDER_PACKAGE,
            attachmentUri = attachmentUri
        )
        rememberPreParse(container, requestId, preParsed, preParsedTotal, totals, preParsedItems)
    }

    /**
     * A photo attached to an already-saved expense after the fact — see
     * [LlmTasks.EXPENSE_LINEITEMS_RESCAN]. By this point Vision has already OCR'd that photo (see
     * [com.voxapps.expenses.receiver.OcrResultReceiver]'s EXPENSE_LINEITEMS_RESCAN branch) exactly
     * like a fresh scan or a stub retry — this is the same two-step "OCR text, then LLM cleanup"
     * shape as [send], just tagged with a task family
     * [com.voxapps.expenses.receiver.LlmResultReceiver] routes into a review-and-apply suggestion
     * instead of overwriting the record directly (unlike retry, which only ever targets a
     * never-reviewed stub). [attachmentUri] is optional and additive, same as everywhere else —
     * gated by the caller on `attachPhotoOnRetry`, not required for this to do anything useful.
     */
    /** [sourceGroupId] is the attachment group (see `AttachmentEntity.groupId`) this rescan's photo(s)
     *  belong to, if any — embedded in the task string so [com.voxapps.expenses.receiver.LlmResultReceiver]
     *  can stamp it onto the resulting [com.voxapps.expenses.data.PendingFieldSuggestion], letting a
     *  dismiss of that suggestion also remove the scan that produced it. */
    suspend fun sendLineItemsRescan(context: Context, container: ExpensesContainer, expenseId: Long, rawText: String, attachmentUri: String? = null, sourceGroupId: String? = null) {
        val existingCategories = container.expensesRepository.categories.first().map { it.name }
        val settings = container.settingsRepository.getSnapshot()
        // Prompts and regex parsers see the PLAIN reading-order text — the appended table
        // reconstruction (see TableItemsPreParse.TABLE_SECTION_MARKER) exists only for the
        // deterministic items gate, so a reconstruction misfire can never degrade the LLM's input.
        val plainText = TableItemsPreParse.plainText(rawText)
        val preParsed = DateTimeRegexParser.parse(plainText)
        val totals = ReceiptTotalRegexParser.parse(plainText)
        val preParsedTotal = totals.total
        val preParsedItems = TableItemsPreParse.parse(rawText, totals.invoiceTotal ?: totals.total)

        // Always the full prompt, whatever the engine says: this action exists only to fetch line
        // items, and asking for them without asking for them is a guaranteed wasted round trip.
        val promptText = ExpenseScanCleanupPromptBuilder.build(
            plainText,
            existingCategories,
            settings.defaultCurrency,
            settings.language,
            preParsedDate = preParsed.date,
            preParsedTime = preParsed.time,
            includeLineItems = true,
        )

        Logger.d(TAG, "Sending ACTION_LLM_PROCESS to $COMMANDER_PACKAGE for line-items rescan (expenseId=$expenseId)")
        val requestId = container.pendingLlmRequestQueue.enqueueAndSend(
            context = context,
            sourcePackage = context.packageName,
            task = "${LlmTasks.EXPENSE_LINEITEMS_RESCAN}:$expenseId:${sourceGroupId.orEmpty()}",
            promptText = promptText,
            targetPackage = COMMANDER_PACKAGE,
            attachmentUri = attachmentUri
        )
        rememberPreParse(container, requestId, preParsed, preParsedTotal, totals, preParsedItems)
    }

    /**
     * Creates a NEW expense from a not-yet-saved multi-shot (or single-shot) "Scan" capture — see
     * [com.voxapps.expenses.ui.ExpensesScreen]. Unlike [send], this never populates
     * `Expense.receiptImageName`/`receipts/` staging: every scanned page is already staged as a plain
     * file under [com.voxapps.expenses.data.ExpensesAttachments.DIR] (see
     * [com.voxapps.expenses.receiver.OcrResultReceiver]'s pending-scan branches), and
     * [com.voxapps.expenses.receiver.LlmResultReceiver] links them as ordinary (grouped, if [groupId]
     * is non-null) AttachmentEntity rows once the new expense's id is known — win or lose (a failed
     * parse still creates a stub holding the photo(s), same as the old imageName-based recovery flow
     * did for a single photo).
     */
    suspend fun sendPendingCreate(
        context: Context,
        container: ExpensesContainer,
        rawText: String,
        fileNames: List<String>,
        groupId: String?,
        attachmentUri: String? = null
    ) {
        val existingCategories = container.expensesRepository.categories.first().map { it.name }
        val settings = container.settingsRepository.getSnapshot()
        // Prompts and regex parsers see the PLAIN reading-order text — the appended table
        // reconstruction (see TableItemsPreParse.TABLE_SECTION_MARKER) exists only for the
        // deterministic items gate, so a reconstruction misfire can never degrade the LLM's input.
        val plainText = TableItemsPreParse.plainText(rawText)
        val preParsed = DateTimeRegexParser.parse(plainText)
        val totals = ReceiptTotalRegexParser.parse(plainText)
        val preParsedTotal = totals.total
        val preParsedItems = TableItemsPreParse.parse(rawText, totals.invoiceTotal ?: totals.total)

        // What the configured engine can take decides how much of the prompt is worth sending;
        // an unreachable or older Commander answers no, which is the shape a weak engine can still
        // complete. See VoxCapabilityClient.EngineCapabilities.longPrompt.
        val includeLineItems = com.voxapps.ipc.VoxCapabilityClient.supportsLongPrompt(context)

        val promptText = ExpenseScanCleanupPromptBuilder.build(
            plainText,
            existingCategories,
            settings.defaultCurrency,
            settings.language,
            preParsedDate = preParsed.date,
            preParsedTime = preParsed.time,
            includeLineItems = includeLineItems,
        )

        val taskWithMeta = "${LlmTasks.EXPENSE_SCAN_CLEANUP}:pending:${groupId.orEmpty()}:${fileNames.joinToString(",")}"

        Logger.d(TAG, "Sending ACTION_LLM_PROCESS to $COMMANDER_PACKAGE for pending scan create (pages=${fileNames.size})")
        val requestId = container.pendingLlmRequestQueue.enqueueAndSend(
            context = context,
            sourcePackage = context.packageName,
            task = taskWithMeta,
            promptText = promptText,
            targetPackage = COMMANDER_PACKAGE,
            attachmentUri = attachmentUri
        )
        rememberPreParse(container, requestId, preParsed, preParsedTotal, totals, preParsedItems)
    }

    /**
     * Holds what regex already established until the reply comes back. A field the prompt told the
     * model to skip is absent from that reply by design, so the value has to survive the round trip
     * somewhere; without this the record falls back as though nothing had been found.
     */
    private suspend fun rememberPreParse(
        container: ExpensesContainer,
        requestId: String,
        preParsed: DateTimeRegexParser.Result,
        preParsedTotal: Double?,
        totals: ReceiptTotalRegexParser.Result,
        preParsedItems: List<TableItemsPreParse.Item>?
    ) {
        container.scanPreParseRepository.put(
            requestId,
            ScanPreParse(
                date = preParsed.date,
                time = preParsed.time,
                total = preParsedTotal,
                itemsJson = preParsedItems?.let { TableItemsPreParse.toJson(it) },
                previousBalance = totals.previousBalance,
                invoiceOwnTotal = totals.invoiceTotal
            )
        )
    }
}
