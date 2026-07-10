package com.voxapps.notes.state

import android.content.Context
import com.voxapps.notes.data.Category
import com.voxapps.notes.data.Note
import com.voxapps.notes.data.NotesRepository
import com.voxapps.notes.data.preferences.NotesSettingsRepository
import com.voxapps.notes.domain.llm.CategoryAutoMergeScheduler
import com.voxapps.notes.domain.llm.CategoryMergeRequestSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Reactive state hub for VoxNotes (mirrors vox-commander's AppStateManager). Combines persisted
 * settings + Room flows + ephemeral runtime (filters + session tick) into a single [uiState] that is
 * either [NotesUiState.Locked] or [NotesUiState.Unlocked]. Persistence is delegated to
 * [NotesRepository]; the biometric session lives in [SessionManager].
 */
class NotesStateManager internal constructor(
    private val settingsRepo: NotesSettingsRepository,
    private val notesRepo: NotesRepository,
    private val sessionManager: SessionManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private data class Runtime(
        val selectedCategoryId: Long? = null,
        val sort: SortMode = SortMode.NEWEST,
        val dateFrom: Long? = null,
        val dateTo: Long? = null,
        val sessionTick: Int = 0
    )

    private val _runtime = MutableStateFlow(Runtime())

    private val _uiState = MutableStateFlow<NotesUiState>(NotesUiState.Loading)
    val uiState: StateFlow<NotesUiState> = _uiState.asStateFlow()

    init {
        combine(
            settingsRepo.settingsFlow,
            notesRepo.notesWithCategory,
            notesRepo.categories,
            _runtime
        ) { settings, notes, categories, rt ->
            val locked = settings.isBiometricRequired &&
                !sessionManager.isSessionValid(settings.sessionTimeoutMinutes)
            if (locked) {
                NotesUiState.Locked
            } else {
                NotesUiState.Unlocked(
                    notes = NoteFilter.apply(notes, rt.selectedCategoryId, rt.dateFrom, rt.dateTo, rt.sort),
                    categories = categories,
                    selectedCategoryId = rt.selectedCategoryId,
                    sort = rt.sort,
                    dateFrom = rt.dateFrom,
                    dateTo = rt.dateTo
                )
            }
        }.onEach { _uiState.value = it }.launchIn(scope)
    }

    // --- FILTERS ---
    fun setCategoryFilter(categoryId: Long?) = _runtime.update { it.copy(selectedCategoryId = categoryId) }
    fun setSort(sort: SortMode) = _runtime.update { it.copy(sort = sort) }
    fun setDateFilter(from: Long?, to: Long?) = _runtime.update { it.copy(dateFrom = from, dateTo = to) }
    fun clearDateFilter() = _runtime.update { it.copy(dateFrom = null, dateTo = null) }

    // --- SETTINGS WRITES (delegate to repo; settingsFlow updates uiState reactively) ---
    fun setBiometricRequired(required: Boolean) { scope.launch { settingsRepo.setBiometricRequired(required) } }
    fun setSessionTimeoutMinutes(minutes: Int) { scope.launch { settingsRepo.setSessionTimeoutMinutes(minutes) } }
    fun setDefaultVoiceCategoryId(id: Long?) { scope.launch { settingsRepo.setDefaultVoiceCategoryId(id) } }
    fun setVoiceSaveToastEnabled(enabled: Boolean) { scope.launch { settingsRepo.setVoiceSaveToastEnabled(enabled) } }
    fun setAutoCreateVoiceCategory(enabled: Boolean) { scope.launch { settingsRepo.setAutoCreateVoiceCategory(enabled) } }
    fun setLanguage(code: String) { scope.launch { settingsRepo.setLanguage(code) } }
    fun setScheduledMergeInterval(context: Context, interval: String) {
        scope.launch { settingsRepo.setScheduledMergeInterval(interval) }
        CategoryAutoMergeScheduler.reschedule(context, interval)
    }

    // --- SESSION LOCK ---
    /** Called after a successful biometric auth; opens the read window per the timeout setting. */
    fun unlock() {
        sessionManager.markUnlocked()
        bumpSession()
    }

    fun lock() {
        sessionManager.lock()
        bumpSession()
    }

    /** Force a re-evaluation of lock state (e.g. on app foreground) so an expired session re-locks. */
    fun recheckLock() = bumpSession()

    private fun bumpSession() = _runtime.update { it.copy(sessionTick = it.sessionTick + 1) }

    // --- NOTE CRUD (delegated) ---
    fun addNote(title: String?, text: String, categoryId: Long? = _runtime.value.selectedCategoryId) {
        scope.launch { notesRepo.addNote(title, text, categoryId, System.currentTimeMillis()) }
    }

    fun updateNote(note: Note) { scope.launch { notesRepo.updateNote(note) } }
    fun updateNoteFields(id: Long, title: String?, text: String, categoryId: Long?) {
        scope.launch { notesRepo.updateNoteFields(id, title, text, categoryId) }
    }
    fun deleteNote(note: Note) { scope.launch { notesRepo.deleteNote(note) } }
    fun deleteNoteById(id: Long) { scope.launch { notesRepo.deleteNoteById(id) } }

    // --- CATEGORY CRUD (delegated) ---
    fun addCategory(name: String, colorArgb: Long) {
        val position = (uiStateCategories()).size
        scope.launch { notesRepo.addCategory(name, colorArgb, position, System.currentTimeMillis()) }
    }

    fun updateCategory(category: Category) { scope.launch { notesRepo.updateCategory(category) } }

    fun removeCategory(category: Category) {
        scope.launch {
            notesRepo.deleteCategory(category)
            if (_runtime.value.selectedCategoryId == category.id) setCategoryFilter(null)
        }
    }

    private fun uiStateCategories(): List<Category> =
        (_uiState.value as? NotesUiState.Unlocked)?.categories ?: emptyList()

    /**
     * Fires the Auto-Merge Categories request for exactly [categoryNames] (the caller decides which
     * categories to include — e.g. the manual button only sends the user's checked selection). The
     * scheduled job (see [com.voxapps.notes.domain.llm.CategoryAutoMergeWorker]) gathers all category
     * names itself and calls this with the full list.
     */
    fun requestCategoryAutoMerge(context: Context, categoryNames: List<String>) {
        val language = settingsRepo.getSnapshot().language
        CategoryMergeRequestSender.send(context, categoryNames, language)
    }

    companion object {
        @Volatile private var instance: NotesStateManager? = null

        fun getInstance(
            settingsRepo: NotesSettingsRepository,
            notesRepo: NotesRepository,
            sessionManager: SessionManager
        ): NotesStateManager = instance ?: synchronized(this) {
            instance ?: NotesStateManager(settingsRepo, notesRepo, sessionManager).also { instance = it }
        }
    }
}
