package com.voxapps.notes.di

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.voxapps.notes.data.NotesDatabase
import com.voxapps.notes.data.NotesRepository
import com.voxapps.notes.data.preferences.NotesSettingsRepository
import com.voxapps.notes.data.preferences.NotesSettingsRepositoryImpl
import com.voxapps.notes.domain.llm.NoteDeduplicationRepository
import com.voxapps.notes.domain.localization.LanguageManager
import com.voxapps.notes.state.NotesStateManager
import com.voxapps.notes.state.SessionManager
import com.voxapps.notes.ui.widget.NotesWidget
import com.voxapps.ipc.VoxLlmRequestQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Manual DI container for VoxNotes (mirrors vox-commander's AppContainer). Owns all singletons and
 * is constructed once from [com.voxapps.notes.NotesApplication.onCreate].
 */
class NotesContainer(context: Context) {
    private val appContext = context.applicationContext

    val settingsRepository: NotesSettingsRepository = NotesSettingsRepositoryImpl(appContext)

    private val database = NotesDatabase.get(appContext)
    val notesRepository = NotesRepository(database.noteDao(), database.categoryDao())
    val attachmentDao = database.attachmentDao()

    val noteDeduplicationRepository = NoteDeduplicationRepository(appContext)
    val pendingLlmRequestQueue = VoxLlmRequestQueue(database.pendingLlmRequestDao())

    val sessionManager = SessionManager()

    val notesStateManager = NotesStateManager.getInstance(
        settingsRepository,
        notesRepository,
        sessionManager,
        noteDeduplicationRepository,
        pendingLlmRequestQueue,
        attachmentDao
    )

    val languageManager = LanguageManager(appContext).also {
        it.loadLanguage(settingsRepository.getSnapshot().language)
    }

    init {
        // Keeps NotesWidget's home-screen snapshot fresh — reacts to both lock-state transitions
        // (uiState) and data changes (notesWithCategory), since the widget reads both independently
        // of any in-app filter (see NotesWidget.kt's provideGlance doc comment).
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            combine(notesStateManager.uiState, notesRepository.notesWithCategory) { _, _ -> }
                .collect { NotesWidget().updateAll(appContext) }
        }
    }
}
