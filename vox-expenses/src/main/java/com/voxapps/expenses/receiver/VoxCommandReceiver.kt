package com.voxapps.expenses.receiver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.voxapps.backup.VoxBackupDispatch
import com.voxapps.backup.VoxImportMode
import com.voxapps.expenses.ExpensesApplication
import com.voxapps.expenses.domain.llm.ExpenseParsePromptBuilder
import com.voxapps.expenses.domain.llm.ExpenseParseRequestSender
import com.voxapps.expenses.domain.llm.GeneratedParsedSchema
import com.voxapps.expenses.domain.llm.LlmTasks
import com.voxapps.ipc.VoxCommand
import com.voxapps.ipc.VoxFormSchema
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxResult
import com.voxapps.ipc.VoxSatelliteSchema
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The satellite's entry point for Commander's command bus — NO NLU here either, just a `when(op)`
 * (mirrors vox-notes' VoxCommandReceiver). Unlike Notes, `create` never inserts synchronously: a raw
 * spoken utterance needs real structured extraction (amount/vendor/items), so it only fires the
 * generic-LLM-hook parse request (see [ExpenseParseRequestSender]) — the actual insert happens later
 * in [LlmResultReceiver] once the async reply arrives.
 *
 * Guarded by the shared `com.voxapps.vox.permission.COMMAND` custom permission (declared once in
 * `:core:ipc`'s manifest).
 */
class VoxCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != VoxIpc.ACTION_COMMAND) return
        val command = VoxCommand.fromJson(intent.getStringExtra(VoxIpc.EXTRA_PAYLOAD)) ?: return

        val container = (context.applicationContext as ExpensesApplication).container

        when (command.op) {
            VoxIpc.OP_PING -> {
                // Handshake for Commander's "Vox Apps" discovery — no DB, no auth.
                setResult(Activity.RESULT_OK, VoxResult(ok = true, text = "pong").toJson(), null)
            }

            VoxIpc.OP_CREATE -> {
                val text = command.text.orEmpty()
                if (text.isBlank()) return
                val settings = container.settingsRepository.getSnapshot()
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val categoryNames = container.expensesRepository.categories.first().map { it.name }
                        ExpenseParseRequestSender.send(
                            context = context.applicationContext,
                            queue = container.pendingLlmRequestQueue,
                            rawText = text,
                            existingCategories = categoryNames,
                            defaultCurrency = settings.defaultCurrency,
                            languageCode = settings.language
                        )
                    } finally {
                        pending.finish()
                    }
                }
            }

            VoxIpc.OP_GET_SCHEMA -> {
                // See the collapsed voice-command plan: Commander fetches and caches this once
                // (Integrations' Refresh button), not per voice command. needsExtractionPass=true
                // because a raw utterance needs real distributive/cumulative-price reasoning that
                // Commander's own classification call isn't positioned to resolve.
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val settings = container.settingsRepository.getSnapshot()
                        val categoryNames = container.expensesRepository.categories.first().map { it.name }
                        val schema = VoxSatelliteSchema(
                            needsExtractionPass = true,
                            promptTemplate = ExpenseParsePromptBuilder.buildTemplate(
                                categoryNames, settings.defaultCurrency, settings.language
                            ),
                            fieldSchemaVersion = GeneratedParsedSchema.VERSION,
                            taskId = LlmTasks.EXPENSE_PARSE
                        )
                        pending.setResultData(VoxResult(ok = true, text = schema.toJson()).toJson())
                    } finally {
                        pending.finish()
                    }
                }
            }

            VoxIpc.OP_READ -> {
                // Async within the ordered-broadcast window; the lock decision (and whether the DB
                // is touched at all) lives in ExpensesReadResponder so it's unit-testable.
                val responder = ExpensesReadResponder(
                    container.settingsRepository,
                    container.sessionManager,
                    container.expensesRepository
                )
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        // Must use the PendingResult's own setResultData, not the inherited
                        // BroadcastReceiver.setResult() — the latter throws "Call while result is
                        // not pending" once called from outside onReceive()'s synchronous window,
                        // which goAsync()'s whole point is to let us do.
                        pending.setResultData(responder.respond(command.dateFrom, command.dateTo).toJson())
                    } finally {
                        pending.finish()
                    }
                }
            }

            VoxIpc.OP_EXPORT -> {
                val handler = ExpensesExportImportHandler(
                    context.applicationContext,
                    container.settingsRepository,
                    container.sessionManager,
                    container.expensesRepository,
                    container.attachmentDao,
                    container.duplicateRuleDao
                )
                val scope = command.exportScope ?: VoxIpc.EXPORT_SCOPE_BOTH
                VoxBackupDispatch.dispatch(this) {
                    handler.export(scope, command.includeSecrets, command.includePhotos)
                }
            }

            VoxIpc.OP_IMPORT -> {
                val handler = ExpensesExportImportHandler(
                    context.applicationContext,
                    container.settingsRepository,
                    container.sessionManager,
                    container.expensesRepository,
                    container.attachmentDao,
                    container.duplicateRuleDao
                )
                VoxBackupDispatch.dispatch(this) {
                    handler.import(command.text.orEmpty(), VoxImportMode.fromWireValue(command.importMode))
                }
            }

            VoxIpc.OP_SYNC_EXPORT -> {
                val handler = ExpensesSyncHandler(
                    container.settingsRepository,
                    container.sessionManager,
                    container.expensesRepository
                )
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        pending.setResultData(handler.export(command.since ?: 0L, command.scopeNames).toJson())
                    } finally {
                        pending.finish()
                    }
                }
            }

            VoxIpc.OP_SYNC_MERGE -> {
                val handler = ExpensesSyncHandler(
                    container.settingsRepository,
                    container.sessionManager,
                    container.expensesRepository
                )
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        pending.setResultData(handler.merge(command.text.orEmpty()).toJson())
                    } finally {
                        pending.finish()
                    }
                }
            }

            VoxIpc.OP_GET_FIELD_SCHEMA -> {
                // Field keys/types mirror ExpensesSyncHandler.toSyncJson() exactly — this describes
                // the same wire shape sync_export/sync_merge already use, not a new one.
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val categoryNames = container.expensesRepository.categories.first().map { it.name }
                        val schema = VoxFormSchema.domainSchema(
                            domain = "expenses",
                            titleField = "title",
                            titleFallbackField = "vendor",
                            subtitleFields = listOf("categoryName"),
                            sortField = "dateTime",
                            fields = listOf(
                                VoxFormSchema.field("title", "Title", "text"),
                                VoxFormSchema.field("totalAmount", "Total amount", "number", required = true),
                                VoxFormSchema.field("currencyCode", "Currency", "text"),
                                VoxFormSchema.field("vendor", "Vendor", "text"),
                                VoxFormSchema.field("bank", "Bank", "text"),
                                VoxFormSchema.field("location", "Location", "text"),
                                VoxFormSchema.field("comments", "Comments", "text"),
                                VoxFormSchema.field("dateTime", "Date", "datetime", required = true),
                                VoxFormSchema.field("categoryName", "Category", "category", options = categoryNames),
                                VoxFormSchema.field(
                                    "direction", "Direction", "enum",
                                    required = true, options = listOf("OUTGOING", "INCOMING")
                                ),
                                VoxFormSchema.field(
                                    "lineItems", "Line items", "list",
                                    itemFields = listOf(
                                        VoxFormSchema.field("name", "Name", "text"),
                                        VoxFormSchema.field("quantity", "Qty", "number"),
                                        VoxFormSchema.field("unitPrice", "Unit price", "number"),
                                        VoxFormSchema.field("netAmount", "Net", "number"),
                                        VoxFormSchema.field("vatAmount", "VAT", "number"),
                                        VoxFormSchema.field("grossAmount", "Gross", "number"),
                                    )
                                ),
                                VoxFormSchema.field("receiptImageName", "Receipt", "readonly"),
                            )
                        )
                        pending.setResultData(VoxResult(ok = true, text = schema.toString()).toJson())
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}
