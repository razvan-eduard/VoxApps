package com.voxapps.expenses

import android.app.Application
import com.voxapps.expenses.di.ExpensesContainer
import com.voxapps.expenses.domain.limits.SpendingLimitScheduler
import com.voxapps.expenses.domain.llm.CategoryAutoMergeScheduler
import com.voxapps.expenses.domain.llm.ExpenseDeduplicationScheduler
import com.voxapps.expenses.domain.llm.ExpenseParsePromptBuilder
import com.voxapps.expenses.domain.llm.GeneratedParsedSchema
import com.voxapps.expenses.domain.llm.LlmTasks
import com.voxapps.ipc.VoxDataTransferClient
import com.voxapps.ipc.VoxSatelliteSchema
import com.voxapps.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

class ExpensesApplication : Application() {
    lateinit var container: ExpensesContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = ExpensesContainer(this)

        // Re-assert both WorkManager schedules on every process start (idempotent via
        // ExistingPeriodicWorkPolicy.UPDATE) so a setting change made while the process was dead is
        // still honored (mirrors vox-notes' NotesApplication).
        val settingsSnapshot = container.settingsRepository.getSnapshot()
        CategoryAutoMergeScheduler.reschedule(this, settingsSnapshot.scheduledMergeInterval)
        ExpenseDeduplicationScheduler.reschedule(this, settingsSnapshot.scheduledExpenseDedupInterval)
        SpendingLimitScheduler.ensureScheduled(this)

        // Apply the persisted debug-logging flag immediately, then keep it in sync with any later
        // Settings toggle (mirrors vox-notes' NotesApplication).
        Logger.initialize(this, "VoxExpenses")
        Logger.setEnabled(settingsSnapshot.debugLoggingEnabled)
        Logger.setToastsEnabled(settingsSnapshot.debugToastsEnabled, this)
        container.settingsRepository.settingsFlow
            .map { it.debugLoggingEnabled to it.debugToastsEnabled }
            .distinctUntilChanged()
            .onEach { (logging, toasts) -> 
                Logger.setEnabled(logging)
                Logger.setToastsEnabled(toasts, this)
            }
            .launchIn(CoroutineScope(SupervisorJob() + Dispatchers.Default))

        // Push a corrected schema to Commander's cache the instant our own category list changes —
        // dropping the first (initial-load) emission so this only fires on real mutations, whichever
        // call site caused them (manual edit, voice auto-create, import). See the collapsed
        // voice-command plan's section 5: a precise, verified-event push, not a poll or timer, and the
        // one deliberate exception to manual-only cache invalidation, since only Expenses itself can
        // know *for certain*, at the exact moment of a write, that Commander's cache is now wrong.
        container.expensesRepository.categories
            .drop(1)
            .map { it.map { c -> c.name } }
            .distinctUntilChanged()
            .onEach { categoryNames ->
                val settings = container.settingsRepository.getSnapshot()
                val schema = VoxSatelliteSchema(
                    needsExtractionPass = true,
                    promptTemplate = ExpenseParsePromptBuilder.buildTemplate(
                        categoryNames, settings.defaultCurrency, settings.language
                    ),
                    fieldSchemaVersion = GeneratedParsedSchema.VERSION,
                    taskId = LlmTasks.EXPENSE_PARSE
                )
                VoxDataTransferClient.pushSchemaChanged(this, schema)
            }
            .launchIn(CoroutineScope(SupervisorJob() + Dispatchers.IO))
    }
}
