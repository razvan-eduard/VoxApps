package com.voxapps.expenses.receiver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.voxapps.expenses.ExpensesApplication
import com.voxapps.expenses.domain.llm.ExpenseParseRequestSender
import com.voxapps.ipc.VoxCommand
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxResult
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
                    container.settingsRepository,
                    container.sessionManager,
                    container.expensesRepository
                )
                val pending = goAsync()
                val scope = command.exportScope ?: VoxIpc.EXPORT_SCOPE_BOTH
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        pending.setResultData(handler.export(scope).toJson())
                    } finally {
                        pending.finish()
                    }
                }
            }

            VoxIpc.OP_IMPORT -> {
                val handler = ExpensesExportImportHandler(
                    container.settingsRepository,
                    container.sessionManager,
                    container.expensesRepository
                )
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        pending.setResultData(handler.import(command.text.orEmpty()).toJson())
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}
