package com.voxapps.calendarapp.di

import android.content.Context
import com.voxapps.calendarapp.data.CalendarDatabase
import com.voxapps.calendarapp.data.CalendarRepository
import com.voxapps.calendarapp.data.preferences.CalendarSettingsRepository
import com.voxapps.calendarapp.data.preferences.CalendarSettingsRepositoryImpl
import com.voxapps.calendarapp.domain.localization.LanguageManager
import com.voxapps.calendarapp.state.CalendarStateManager
import com.voxapps.calendarapp.state.SessionManager
import androidx.glance.appwidget.updateAll
import com.voxapps.calendarapp.ui.widget.CalendarWidget
import com.voxapps.ipc.VoxLlmRequestQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    val calendarRepository = CalendarRepository(
        database.calendarEntryDao(),
        database.calendarLayerDao(),
        database.calendarEntryTagDao(),
        attachmentDao,
        appContext
    )

    val pendingLlmRequestQueue = VoxLlmRequestQueue(database.pendingLlmRequestDao())

    val sessionManager = SessionManager()

    val calendarStateManager = CalendarStateManager.getInstance(
        settingsRepository,
        calendarRepository,
        sessionManager,
        attachmentDao
    )

    val languageManager = LanguageManager(appContext).also {
        it.loadLanguage(settingsRepository.getSnapshot().language)
    }

    init {
        // Keeps CalendarWidget's home-screen snapshot in sync with every uiState change (add/edit/
        // delete, lock/unlock, settings) — one central hook here instead of scattering updateAll()
        // calls across every write path in CalendarStateManager/CalendarRepository.
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            calendarStateManager.uiState.collect { CalendarWidget().updateAll(appContext) }
        }
    }
}
