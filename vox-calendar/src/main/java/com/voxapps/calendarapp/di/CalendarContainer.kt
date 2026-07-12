package com.voxapps.calendarapp.di

import android.content.Context
import com.voxapps.calendarapp.data.CalendarDatabase
import com.voxapps.calendarapp.data.CalendarRepository
import com.voxapps.calendarapp.data.preferences.CalendarSettingsRepository
import com.voxapps.calendarapp.data.preferences.CalendarSettingsRepositoryImpl
import com.voxapps.calendarapp.domain.localization.LanguageManager
import com.voxapps.calendarapp.state.CalendarStateManager
import com.voxapps.calendarapp.state.SessionManager

/**
 * Manual DI container for Vox Calendar (mirrors vox-expenses' ExpensesContainer). Owns all singletons
 * and is constructed once from [com.voxapps.calendarapp.CalendarApplication.onCreate].
 */
class CalendarContainer(context: Context) {
    private val appContext = context.applicationContext

    val settingsRepository: CalendarSettingsRepository = CalendarSettingsRepositoryImpl(appContext)

    private val database = CalendarDatabase.get(appContext)
    val calendarRepository = CalendarRepository(
        database.calendarEntryDao(),
        database.calendarLayerDao(),
        database.calendarEntryTagDao()
    )

    val sessionManager = SessionManager()

    val calendarStateManager = CalendarStateManager.getInstance(
        settingsRepository,
        calendarRepository,
        sessionManager
    )

    val languageManager = LanguageManager(appContext).also {
        it.loadLanguage(settingsRepository.getSnapshot().language)
    }
}
