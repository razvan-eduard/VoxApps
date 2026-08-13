package com.voxapps.expenses

import android.app.Application
import com.voxapps.expenses.di.ExpensesContainer
import com.voxapps.expenses.domain.limits.SpendingLimitScheduler
import com.voxapps.expenses.domain.llm.CategoryAutoMergeScheduler
import com.voxapps.expenses.domain.llm.ExpenseDeduplicationScheduler
import com.voxapps.expenses.domain.llm.ExpenseParsePromptBuilder
import com.voxapps.expenses.domain.llm.GeneratedParsedSchema
import com.voxapps.expenses.domain.llm.LlmTasks
import com.voxapps.expenses.domain.llm.PendingLlmRequestScheduler
import com.voxapps.expenses.domain.widget.WidgetMidnightRefreshScheduler
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
import kotlinx.coroutines.launch
import com.voxapps.services.SchemaCatalog
import com.voxapps.services.SchemaRepo
import com.voxapps.expenses.data.ExternalServiceConfig

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
        PendingLlmRequestScheduler.ensureScheduled(this)
        WidgetMidnightRefreshScheduler.ensureScheduled(this)

        // The services this app talks to but does not own — the rate provider's endpoint and the URL
        // where its key is obtained. Bundled copy first so nothing waits on the network, then the
        // repository's, which is how a moved endpoint gets corrected without an app release.
        SchemaRepo.appFolder = "expenses"
        ExternalServiceConfig.init(this)
        com.voxapps.expenses.data.FieldVocabularies.init(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            // The repository this app follows, and whether it is asked at all — its own settings,
            // so an install can follow a fork here and not in Commander.
            if (settingsSnapshot.useRemoteSchemas) {
                SchemaCatalog.refreshAll(settingsSnapshot.schemaRepoBaseUrl)
            }
        }

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
