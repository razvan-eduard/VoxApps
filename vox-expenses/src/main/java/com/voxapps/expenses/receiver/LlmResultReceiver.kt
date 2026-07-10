package com.voxapps.expenses.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.voxapps.expenses.ExpensesApplication
import com.voxapps.expenses.data.ExpenseLineItem
import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.expenses.di.ExpensesContainer
import com.voxapps.expenses.domain.llm.CategoryMergeMappingParser
import com.voxapps.expenses.domain.llm.ExpenseDeduplicationResultParser
import com.voxapps.expenses.domain.llm.ExpenseParseResultParser
import com.voxapps.expenses.domain.llm.LlmTasks
import com.voxapps.expenses.domain.llm.NotificationExpenseParseResultParser
import com.voxapps.expenses.domain.llm.PendingNotificationExpense
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxLlmResult
import com.voxapps.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "LlmResultReceiver"

/**
 * Expenses' end of Commander's generic LLM hook: receives the async [VoxIpc.ACTION_LLM_RESULT] reply
 * and routes it by [VoxLlmResult.task] (mirrors vox-notes' equivalent receiver). Guarded by Expenses'
 * own `com.voxapps.expenses.permission.LLM_RESULT` signature permission.
 *
 * [LlmTasks.EXPENSE_PARSE] (voice) and [LlmTasks.EXPENSE_SCAN_CLEANUP] (OCR) produce the exact same
 * JSON shape — both parsed with [ExpenseParseResultParser] and created via the same
 * [createExpenseFromParsed] path, rather than duplicating that logic per source.
 */
class LlmResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != VoxIpc.ACTION_LLM_RESULT) return
        val result = VoxLlmResult.fromJson(intent.getStringExtra(VoxIpc.EXTRA_LLM_PAYLOAD)) ?: return
        val container = (context.applicationContext as ExpensesApplication).container

        when (result.task) {
            LlmTasks.EXPENSE_PARSE, LlmTasks.EXPENSE_SCAN_CLEANUP -> {
                val rawJson = result.rawJson
                if (result.status != VoxLlmResult.STATUS_SUCCESS || rawJson == null) {
                    Logger.w(TAG, "${result.task} failed: ${result.error}")
                    return
                }
                val parsed = ExpenseParseResultParser.parse(rawJson) ?: run {
                    Logger.w(TAG, "${result.task}: could not parse LLM result (no amount?). rawJson=$rawJson")
                    return
                }
                Logger.d(TAG, "${result.task}: creating expense total=${parsed.totalAmount} category=${parsed.category}")
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        createExpenseFromParsed(context.applicationContext, container, parsed)
                    } finally {
                        pending.finish()
                    }
                }
            }

            LlmTasks.CATEGORY_DEDUPLICATION -> {
                val rawJson = result.rawJson
                if (result.status != VoxLlmResult.STATUS_SUCCESS || rawJson == null) {
                    Logger.w(TAG, "Category auto-merge failed: ${result.error}")
                    return
                }
                val mapping = CategoryMergeMappingParser.parse(rawJson) ?: run {
                    Logger.w(TAG, "Category auto-merge: could not parse LLM mapping. rawJson=$rawJson")
                    return
                }
                // Deliberately NOT applied here, unlike vox-notes — merging expense categories can
                // reshuffle real financial data/reporting, so the suggestion is stored for review.
                Logger.d(TAG, "Category auto-merge: storing ${mapping.size} proposed mapping(s) for review")
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        container.pendingCategoryMergeRepository.setPendingMapping(mapping)
                    } finally {
                        pending.finish()
                    }
                }
            }

            LlmTasks.EXPENSE_DEDUPLICATION -> {
                val rawJson = result.rawJson
                if (result.status != VoxLlmResult.STATUS_SUCCESS || rawJson == null) {
                    Logger.w(TAG, "Expense deduplication failed: ${result.error}")
                    return
                }
                val groups = ExpenseDeduplicationResultParser.parse(rawJson) ?: run {
                    Logger.w(TAG, "Expense deduplication: could not parse LLM result. rawJson=$rawJson")
                    return
                }
                Logger.d(TAG, "Expense deduplication: storing ${groups.size} proposed group(s) for review")
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        container.expenseDeduplicationRepository.setPendingGroups(groups)
                    } finally {
                        pending.finish()
                    }
                }
            }

            LlmTasks.NOTIFICATION_EXPENSE_PARSE -> {
                val rawJson = result.rawJson
                if (result.status != VoxLlmResult.STATUS_SUCCESS || rawJson == null) {
                    Logger.w(TAG, "Notification expense parse failed: ${result.error}")
                    return
                }
                // A null result here just as often means "the LLM correctly said this wasn't a
                // payment" as it does a genuine parse failure — either way, nothing to review.
                val parsed = NotificationExpenseParseResultParser.parse(rawJson) ?: run {
                    Logger.d(TAG, "Notification expense parse: not a payment or unparseable, discarding")
                    return
                }
                Logger.d(TAG, "Notification expense parse: storing 1 pending entry for review")
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val settings = container.settingsRepository.getSnapshot()
                        container.pendingNotificationExpenseRepository.addPending(
                            PendingNotificationExpense(
                                id = System.nanoTime(),
                                title = parsed.title,
                                totalAmount = parsed.totalAmount,
                                currency = parsed.currency ?: settings.defaultCurrency,
                                vendor = parsed.vendor,
                                category = parsed.category,
                                capturedAt = System.currentTimeMillis()
                            )
                        )
                    } finally {
                        pending.finish()
                    }
                }
            }

            // Future LLM-backed features add a branch here — zero Commander/:core:ipc changes needed.
            else -> Logger.d(TAG, "Ignoring unknown LLM task: ${result.task}")
        }
    }

    private suspend fun createExpenseFromParsed(
        appContext: Context,
        container: ExpensesContainer,
        parsed: ExpenseParseResultParser.Parsed
    ) {
        val settings: ExpensesSettings = container.settingsRepository.getSnapshot()
        val items = parsed.items.map {
            ExpenseLineItem(expenseId = 0, name = it.name, quantity = it.quantity, unitPrice = it.unitPrice)
        }
        val resolved = container.expensesRepository.addParsedExpense(
            title = parsed.title,
            totalAmount = parsed.totalAmount,
            currencyCode = parsed.currency ?: settings.defaultCurrency,
            vendor = parsed.vendor,
            bank = null,
            location = null,
            comments = null,
            dateTime = System.currentTimeMillis(),
            spokenCategory = parsed.category,
            defaultCategoryId = settings.defaultVoiceCategoryId,
            autoCreate = settings.autoCreateVoiceCategory,
            items = items
        )
        if (settings.voiceSaveToastEnabled) {
            val label = parsed.title?.takeIf { it.isNotBlank() } ?: parsed.vendor ?: parsed.totalAmount.toString()
            val template = container.languageManager.getString("toast_expense_saved")
            val msg = String.format(template, label) + (resolved.name?.let { " · $it" } ?: "")
            withContext(Dispatchers.Main) {
                Toast.makeText(appContext, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
