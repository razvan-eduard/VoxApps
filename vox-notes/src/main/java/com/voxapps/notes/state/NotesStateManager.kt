package com.voxapps.notes.state

import android.content.Context
import com.voxapps.attachments.AttachmentDao
import com.voxapps.attachments.AttachmentEntity
import com.voxapps.attachments.AttachmentFileStore
import com.voxapps.attachments.AttachmentSource
import com.voxapps.notes.data.Category
import com.voxapps.notes.data.NotesAttachments
import com.voxapps.notes.data.Note
import com.voxapps.notes.data.NotesRepository
import com.voxapps.notes.data.preferences.NotesSettingsRepository
import com.voxapps.notes.domain.llm.CategoryAutoMergeScheduler
import com.voxapps.notes.domain.llm.CategoryMergeRequestSender
import com.voxapps.notes.domain.llm.DuplicateGroup
import com.voxapps.notes.domain.llm.NoteDeduplicationRepository
import com.voxapps.notes.domain.llm.NoteDeduplicationRequestSender
import com.voxapps.notes.domain.llm.NoteDeduplicationScheduler
import com.voxapps.notes.domain.llm.NoteSummary
import com.voxapps.ipc.VoxLlmRequestQueue
import com.voxapps.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
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
class NotesStateManager(
    private val settingsRepo: NotesSettingsRepository,
    private val notesRepo: NotesRepository,
    private val sessionManager: SessionManager,
    private val noteDeduplicationRepo: NoteDeduplicationRepository,
    private val pendingLlmRequestQueue: VoxLlmRequestQueue,
    private val attachmentDao: AttachmentDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private data class Runtime(
        val selectedCategoryId: Long? = null,
        val sort: SortMode = SortMode.NEWEST,
        val selectedDateMillis: Long = System.currentTimeMillis(),
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
                    isGridView = settings.isGridView,
                    selectedDateMillis = rt.selectedDateMillis,
                    dateFrom = rt.dateFrom,
                    dateTo = rt.dateTo
                )
            }
        }
        // Deliberately NOT flowOn(Default) and NOT stateIn/WhileSubscribed, though the combine
        // above is real CPU work on the scope's Main dispatcher:
        //
        //  - flowOn moves the transform off this thread, which makes _uiState update
        //    asynchronously. Today a change published into _runtime settles into _uiState within
        //    the same call, and the synchronous accessors below rely on that; adding flowOn broke
        //    exactly those expectations in NotesStateManagerTest.
        //  - WhileSubscribed would leave uiState.value at its initial Loading value whenever no UI
        //    is attached, and it is read synchronously, with no subscription, by headless callers
        //    (IPC read/export responders and the widget refresh).
        //
        // Both are worth revisiting only behind a measurement showing this combine actually costs
        // frames, together with a plan for those synchronous readers.
            .onEach { _uiState.value = it }.launchIn(scope)
    }

    // --- FILTERS ---
    fun setCategoryFilter(categoryId: Long?) = _runtime.update { it.copy(selectedCategoryId = categoryId) }
    fun setSort(sort: SortMode) = _runtime.update { it.copy(sort = sort) }
    fun setSelectedDate(millis: Long) = _runtime.update { it.copy(selectedDateMillis = millis) }
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
    fun setDebugLoggingEnabled(enabled: Boolean) {
        Logger.setEnabled(enabled)
        scope.launch { settingsRepo.setDebugLoggingEnabled(enabled) }
    }
    fun setDebugToastsEnabled(enabled: Boolean) {
        Logger.setToastsEnabled(enabled)
        scope.launch { settingsRepo.setDebugToastsEnabled(enabled) }
    }
    fun setCalendarViewEnabled(enabled: Boolean) { scope.launch { settingsRepo.setCalendarViewEnabled(enabled) } }
    fun setIsGridView(enabled: Boolean) { scope.launch { settingsRepo.setIsGridView(enabled) } }
    fun setAttachPhotoOnScan(enabled: Boolean) { scope.launch { settingsRepo.setAttachPhotoOnScan(enabled) } }
    fun setScanImageRetention(mode: String) { scope.launch { settingsRepo.setScanImageRetention(mode) } }
    fun setScanLlmLevel(level: String) { scope.launch { settingsRepo.setScanLlmLevel(level) } }

    // --- Attachments (generic per-note photos, both the scan-kept one and manually-added ones) ---
    fun observeAttachments(noteId: Long): Flow<List<AttachmentEntity>> =
        attachmentDao.observeFor(NotesAttachments.RECORD_TYPE, noteId)

    fun addManualAttachment(noteId: Long, fileName: String, groupId: String? = null, groupOrder: Int = 0) {
        scope.launch {
            attachmentDao.insert(
                AttachmentEntity(
                    recordType = NotesAttachments.RECORD_TYPE,
                    recordId = noteId,
                    fileName = fileName,
                    source = AttachmentSource.MANUAL,
                    createdAt = System.currentTimeMillis(),
                    groupId = groupId,
                    groupOrder = groupOrder
                )
            )
        }
    }

    fun removeAttachment(entity: AttachmentEntity, context: Context) {
        scope.launch {
            attachmentDao.delete(entity.id)
            AttachmentFileStore.delete(context, NotesAttachments.DIR, entity.fileName)
        }
    }

    /** Cancels a burst mid-capture (see [com.voxapps.attachments.ui.rememberBurstCaptureLauncher]) —
     *  deletes every row+file already committed under [groupId] for this note. */
    fun deleteAttachmentGroup(noteId: Long, groupId: String, context: Context) {
        scope.launch {
            val deleted = attachmentDao.deleteGroup(NotesAttachments.RECORD_TYPE, noteId, groupId)
            deleted.forEach { AttachmentFileStore.delete(context, NotesAttachments.DIR, it.fileName) }
        }
    }
    fun setThemeDarkMode(mode: String) { scope.launch { settingsRepo.setThemeDarkMode(mode) } }
    fun setThemeColored(colored: Boolean) { scope.launch { settingsRepo.setThemeColored(colored) } }
    fun setTodayEffect(effect: String) { scope.launch { settingsRepo.setTodayEffect(effect) } }
    fun setTodayEffectStyle(style: String) { scope.launch { settingsRepo.setTodayEffectStyle(style) } }
    fun setTodayEffectColor(colorArgb: Long) { scope.launch { settingsRepo.setTodayEffectColor(colorArgb) } }
    fun setTodayEffectColor2(colorArgb: Long?) { scope.launch { settingsRepo.setTodayEffectColor2(colorArgb) } }
    fun setTodayEffectSpeed(speed: Float) { scope.launch { settingsRepo.setTodayEffectSpeed(speed) } }

    fun setNotificationsSystemDefault(enabled: Boolean) {
        scope.launch {
            settingsRepo.setNotificationsSystemDefault(enabled)
            if (!enabled) incrementNotificationChannelVersion()
        }
    }

    fun setNotificationsVibrationEnabled(enabled: Boolean) {
        scope.launch {
            settingsRepo.setNotificationsVibrationEnabled(enabled)
            incrementNotificationChannelVersion()
        }
    }

    fun setNotificationsSoundUri(uri: String?) {
        scope.launch {
            settingsRepo.setNotificationsSoundUri(uri)
            incrementNotificationChannelVersion()
        }
    }

    fun setNotificationsVolume(volume: Int) {
        scope.launch {
            settingsRepo.setNotificationsVolume(volume)
        }
    }

    fun setNotificationsLength(length: String) {
        scope.launch {
            settingsRepo.setNotificationsLength(length)
            incrementNotificationChannelVersion()
        }
    }

    private suspend fun incrementNotificationChannelVersion() {
        val current = settingsRepo.getSnapshot().notificationsChannelVersion
        settingsRepo.setNotificationsChannelVersion(current + 1)
    }

    fun setOnboardingCompleted(completed: Boolean) { scope.launch { settingsRepo.setOnboardingCompleted(completed) } }
    fun seedDebugTestData() {
        scope.launch { com.voxapps.notes.domain.debug.DebugDataSeeder.seed(notesRepo) }
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
    fun addNote(
        title: String?,
        text: String,
        categoryId: Long? = _runtime.value.selectedCategoryId,
        textHtml: String? = null,
        onResult: (Long) -> Unit = {}
    ) {
        scope.launch {
            onResult(notesRepo.addNote(title, text, categoryId, System.currentTimeMillis(), textHtml = textHtml))
        }
    }

    fun updateNote(note: Note) { scope.launch { notesRepo.updateNote(note) } }
    fun updateNoteFields(id: Long, title: String?, text: String, categoryId: Long?, textHtml: String? = null) {
        scope.launch { notesRepo.updateNoteFields(id, title, text, categoryId, textHtml = textHtml) }
    }
    fun deleteNote(note: Note) { scope.launch { notesRepo.deleteNote(note) } }
    fun deleteNoteById(id: Long) { scope.launch { notesRepo.deleteNoteById(id) } }

    // --- CATEGORY CRUD (delegated) ---
    fun addCategory(name: String, colorArgb: Long, onResult: (Long) -> Unit = {}) {
        val position = (uiStateCategories()).size
        scope.launch {
            val id = notesRepo.addCategory(name, colorArgb, position, System.currentTimeMillis())
            onResult(id)
        }
    }

    fun updateCategory(category: Category) { scope.launch { notesRepo.updateCategory(category) } }

    fun setDefaultCategory(categoryId: Long) { scope.launch { notesRepo.setDefaultCategory(categoryId) } }

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
        scope.launch { CategoryMergeRequestSender.send(context, pendingLlmRequestQueue, categoryNames, language) }
    }

    /** Fires the note-deduplication request for every current note. See [requestCategoryAutoMerge]. */
    fun requestNoteDeduplication(context: Context) {
        scope.launch {
            val notes = notesRepo.notes.first().map { NoteSummary(it.id, it.title, it.text) }
            NoteDeduplicationRequestSender.send(context, pendingLlmRequestQueue, notes)
        }
    }

    fun setScheduledNoteDedupInterval(context: Context, interval: String) {
        scope.launch { settingsRepo.setScheduledNoteDedupInterval(interval) }
        NoteDeduplicationScheduler.reschedule(context, interval)
    }

    /** Reactive pending-suggestion stream for the review UI in Settings. */
    val pendingNoteDuplicateGroups: Flow<List<DuplicateGroup>> = noteDeduplicationRepo.pendingGroupsFlow

    /** User approved (a subset of) the proposed groups — apply them, then clear the pending set. */
    fun approveNoteDeduplication(groups: List<DuplicateGroup>) {
        scope.launch {
            notesRepo.applyNoteDeduplication(groups)
            noteDeduplicationRepo.clearPendingGroups()
        }
    }

    /** User dismissed the suggestion without applying anything. */
    fun dismissNoteDeduplication() {
        scope.launch { noteDeduplicationRepo.clearPendingGroups() }
    }
}
