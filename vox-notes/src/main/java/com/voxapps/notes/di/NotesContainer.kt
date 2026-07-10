package com.voxapps.notes.di

import android.content.Context
import com.voxapps.notes.data.NotesDatabase
import com.voxapps.notes.data.NotesRepository
import com.voxapps.notes.data.preferences.NotesSettingsRepository
import com.voxapps.notes.data.preferences.NotesSettingsRepositoryImpl
import com.voxapps.notes.domain.localization.LanguageManager
import com.voxapps.notes.state.NotesStateManager
import com.voxapps.notes.state.SessionManager

/**
 * Manual DI container for VoxNotes (mirrors vox-commander's AppContainer). Owns all singletons and
 * is constructed once from [com.voxapps.notes.NotesApplication.onCreate].
 */
class NotesContainer(context: Context) {
    private val appContext = context.applicationContext

    val settingsRepository: NotesSettingsRepository = NotesSettingsRepositoryImpl(appContext)

    private val database = NotesDatabase.get(appContext)
    val notesRepository = NotesRepository(database.noteDao(), database.categoryDao())

    val sessionManager = SessionManager()

    val notesStateManager = NotesStateManager.getInstance(settingsRepository, notesRepository, sessionManager)

    val languageManager = LanguageManager(appContext).also {
        it.loadLanguage(settingsRepository.getSnapshot().language)
    }
}
