package com.voxapps.calendarapp.di

import android.content.Context
import com.voxapps.calendarapp.data.CalendarAttachments
import com.voxapps.calendarapp.data.CalendarDatabase
import com.voxapps.calendarapp.data.CalendarRepository
import com.voxapps.calendarapp.data.ToDoRepository
import com.voxapps.calendarapp.data.preferences.CalendarSettingsRepository
import com.voxapps.calendarapp.data.preferences.CalendarSettingsRepositoryImpl
import com.voxapps.calendarapp.domain.localization.LanguageManager
import com.voxapps.calendarapp.state.CalendarStateManager
import com.voxapps.calendarapp.state.SessionManager
import androidx.glance.appwidget.updateAll
import com.voxapps.calendarapp.ui.widget.CalendarWidget
import com.voxapps.ipc.VoxLlmRequestQueue
import com.voxapps.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

/**
 * Manual DI container for Vox Calendar (mirrors vox-expenses' ExpensesContainer). Owns all singletons
 * and is constructed once from [com.voxapps.calendarapp.CalendarApplication.onCreate].
 */
class CalendarContainer(context: Context) {
    private val appContext = context.applicationContext

    val settingsRepository: CalendarSettingsRepository = CalendarSettingsRepositoryImpl(appContext)

    private val database = CalendarDatabase.get(appContext)
    val attachmentDao = database.attachmentDao()
    val toDoListDao = database.toDoListDao()
    val calendarRepository = CalendarRepository(
        database.calendarEntryDao(),
        database.calendarLayerDao(),
        database.calendarEntryTagDao(),
        attachmentDao,
        database.calendarReminderDao(),
        toDoListDao,
        appContext
    )
    val toDoRepository = ToDoRepository(toDoListDao, database.calendarEntryDao(), calendarRepository)

    val pendingLlmRequestQueue = VoxLlmRequestQueue(database.pendingLlmRequestDao())

    val sessionManager = SessionManager()

    val calendarStateManager = CalendarStateManager(
        settingsRepository,
        calendarRepository,
        sessionManager,
        attachmentDao
    )

    /**
     * What a caller over the IPC bus is told when this app is locked. Read through
     * [languageManager] because it is spoken and displayed by whoever asked — Commander's TTS, Hub's
     * backup screen — so it has to be in the language the user set, not the language it was written
     * in.
     */
    val lockedMessage: String get() = languageManager.getString("locked_message")

    val languageManager = LanguageManager(appContext).also {
        it.loadLanguage(settingsRepository.getSnapshot().language)
    }

    init {
        // Keeps CalendarWidget's home-screen snapshot in sync with lock-state transitions (uiState),
        // raw data changes (entriesWithTags — the widget reads this directly, independent of any
        // in-app layer/tag filter, see CalendarWidget.kt's provideGlance doc comment), AND settings
        // (settingsFlow — border/today-effect color, style, thickness etc. are all read fresh into
        // the widget's content but nothing about changing them touches entries or uiState, so without
        // watching settingsFlow too, a settings-only change would sit un-reflected until the next
        // unrelated data change, the midnight worker, or the OS's 30-min update floor). Mirrors
        // ExpensesContainer's identical combine().
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            combine(
                calendarStateManager.uiState,
                calendarRepository.entriesWithTags,
                settingsRepository.settingsFlow,
                // Entries themselves don't change when an attachment is added/removed on one of
                // them, so the widget's paperclip indicator would otherwise only catch up on the
                // next unrelated refresh (a settings change, the midnight worker, or the OS's 30-min
                // floor) instead of promptly.
                attachmentDao.observeRecordIdsWithAttachments(CalendarAttachments.RECORD_TYPE)
            // conflate(): each emission drives a Glance updateAll() — an IPC round-trip to the
            // launcher — and a bulk import or a P2P sync merge emits once per record, so the
            // widget would be redrawn N times to show one final state. Conflating drops the
            // intermediate values while an update is still in flight, keeping the newest, so the
            // refresh rate is bounded by how fast updateAll() completes rather than by how fast
            // rows are written. No debounce: nothing here is latency-sensitive enough to justify
            // delaying the common single-change case.
            ) { _, entries, _, _ -> entries.size }.conflate().collect { entryCount ->
                Logger.d("CalendarContainer", "Widget refresh triggered (entriesWithTags size=$entryCount)")
                CalendarWidget().updateAll(appContext)
            }
        }
    }
}
