package com.voxapps.expenses.domain.llm

import android.content.Context
import android.content.Intent
import com.voxapps.expenses.di.ExpensesContainer
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxLlmRequest
import com.voxapps.logging.Logger
import kotlinx.coroutines.flow.first

private const val TAG = "ExpenseScanCleanupRequestSender"
private const val COMMANDER_PACKAGE = "com.voxapps.commander"

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

        val preParsed = DateTimeRegexParser.parse(rawText)

        val taskWithMeta = when {
            imageName != null && retryOfExpenseId != null -> "${LlmTasks.EXPENSE_SCAN_CLEANUP}:$imageName:$retryOfExpenseId"
            imageName != null -> "${LlmTasks.EXPENSE_SCAN_CLEANUP}:$imageName"
            else -> LlmTasks.EXPENSE_SCAN_CLEANUP
        }

        val payload = VoxLlmRequest(
            sourcePackage = context.packageName,
            task = taskWithMeta,
            promptText = ExpenseScanCleanupPromptBuilder.build(
                rawText,
                existingCategories,
                settings.defaultCurrency,
                settings.language,
                preParsedDate = preParsed.date,
                preParsedTime = preParsed.time
            ),
            data = emptyList(),
            attachmentUri = attachmentUri
        ).toJson()

        Logger.d(TAG, "Sending ACTION_LLM_PROCESS to $COMMANDER_PACKAGE for scan cleanup (retryOfExpenseId=$retryOfExpenseId, multimodal=${attachmentUri != null})")
        context.sendBroadcast(
            Intent(VoxIpc.ACTION_LLM_PROCESS)
                .setPackage(COMMANDER_PACKAGE)
                .putExtra(VoxIpc.EXTRA_LLM_PAYLOAD, payload)
        )
    }
}
