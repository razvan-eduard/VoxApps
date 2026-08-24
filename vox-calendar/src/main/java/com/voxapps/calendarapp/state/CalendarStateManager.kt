package com.voxapps.calendarapp.state

import android.content.Context
import com.voxapps.attachments.AttachmentDao
import com.voxapps.attachments.AttachmentEntity
import com.voxapps.attachments.AttachmentFileStore
import com.voxapps.attachments.AttachmentSource
import com.voxapps.calendarapp.data.CalendarAttachments
import com.voxapps.calendarapp.data.CalendarEntry
import com.voxapps.calendarapp.data.CalendarEntryType
import com.voxapps.calendarapp.data.CalendarLayer
import com.voxapps.calendarapp.data.CalendarReminder
import com.voxapps.calendarapp.data.CalendarRepository
import com.voxapps.calendarapp.data.RecurrenceFrequency
import com.voxapps.calendarapp.data.preferences.CalendarSettingsRepository
import com.voxapps.calendarapp.domain.subscription.CalendarSubscriptionSyncEngine
import com.voxapps.calendarapp.domain.subscription.IcsUrlFetcher
import com.voxapps.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.voxapps.design.color.VoxColorPalette

/**
 * Reactive state hub for Vox Calendar (mirrors vox-expenses' ExpensesStateManager). Combines persisted
 * settings + Room flows + ephemeral runtime (view mode, filters, session tick) into a single [uiState]
 * that is either [CalendarUiState.Locked] or [CalendarUiState.Unlocked]. Persistence is delegated to
 * [CalendarRepository]; the biometric session lives in [SessionManager].
 *
 * Seeds a single default layer ("Personal") on first-ever launch (empty layer table) so entries always
 * have somewhere to land — [CalendarEntry.layerId] is non-nullable, unlike Notes'/Expenses' category ids.
 */
class CalendarStateManager(
    private val settingsRepo: CalendarSettingsRepository,
    private val calendarRepo: CalendarRepository,
    private val sessionManager: SessionManager,
    private val attachmentDao: AttachmentDao,
    private val fieldCorrectionMemory: com.voxapps.fieldmemory.FieldCorrectionMemory,
    /** What each settings page says the first time — reset together with the tour. */
    private val hintStore: com.voxapps.onboarding.VoxHintStore? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The tour runs again, and every settings page explains itself again — see
     *  [com.voxapps.onboarding.VoxHintStore]. */
    fun replayTutorial() {
        scope.launch {
            hintStore?.resetAll()
            settingsRepo.setOnboardingCompleted(false)
        }
    }

    private data class Runtime(
        val selectedTags: Set<String> = emptySet(),
        val viewMode: CalendarViewMode = CalendarViewMode.MONTH,
        val selectedDateMillis: Long = System.currentTimeMillis(),
        val sessionTick: Int = 0
    )

    private val _runtime = MutableStateFlow(Runtime())

    private val _uiState = MutableStateFlow<CalendarUiState>(CalendarUiState.Loading)
    val uiState: StateFlow<CalendarUiState> = _uiState.asStateFlow()

    // When todoBleedToCalendar is off, strips out exactly the to-do-flavored rows (CalendarEntry.listId
    // != null) from the grid, leaving the row itself — and its reminder — untouched in the database
    // either way. Unification (see CalendarEntry's doc comment) means this is a plain field check now,
    // no separate linked-entry-id join needed.
    private val entriesRespectingBleed = combine(
        calendarRepo.entriesWithTags,
        settingsRepo.settingsFlow
    ) { entries, settings ->
        if (settings.todoBleedToCalendar) entries
        else entries.filter { it.entry.listId == null }
    }

    init {
        scope.launch { seedDefaultLayerIfNeeded() }

        combine(
            settingsRepo.settingsFlow,
            entriesRespectingBleed,
            calendarRepo.layers,
            calendarRepo.distinctTagNames,
            _runtime
        ) { settings, entries, layers, tags, rt ->
            val locked = settings.isBiometricRequired &&
                !sessionManager.isSessionValid(settings.sessionTimeoutMinutes)
            if (locked) {
                CalendarUiState.Locked
            } else {
                val visibleLayerIds = layers.filter { it.visible }.map { it.id }.toSet()
                CalendarUiState.Unlocked(
                    entries = CalendarFilter.apply(entries, visibleLayerIds, rt.selectedTags),
                    layers = layers,
                    availableTags = tags,
                    selectedTags = rt.selectedTags,
                    viewMode = rt.viewMode,
                    isGridView = settings.isGridView,
                    selectedDateMillis = rt.selectedDateMillis
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

    private suspend fun seedDefaultLayerIfNeeded() {
        if (calendarRepo.layersSnapshot().isEmpty()) {
            calendarRepo.addLayer(
                name = "Personal",
                colorArgb = VoxColorPalette.presets.first(),
                position = 0,
                isDefault = true
            )
        }
    }

    // --- VIEW / FILTERS ---
    fun setViewMode(mode: CalendarViewMode) = _runtime.update { it.copy(viewMode = mode) }
    fun setSelectedDate(millis: Long) = _runtime.update { it.copy(selectedDateMillis = millis) }
    fun setSelectedTags(tags: Set<String>) = _runtime.update { it.copy(selectedTags = tags) }
    fun toggleTag(tag: String) = _runtime.update {
        it.copy(selectedTags = if (tag in it.selectedTags) it.selectedTags - tag else it.selectedTags + tag)
    }

    // --- SETTINGS WRITES ---
    fun setBiometricRequired(required: Boolean) { scope.launch { settingsRepo.setBiometricRequired(required) } }
    fun setSessionTimeoutMinutes(minutes: Int) { scope.launch { settingsRepo.setSessionTimeoutMinutes(minutes) } }
    fun setLanguage(code: String) { scope.launch { settingsRepo.setLanguage(code) } }
    fun setDefaultLayerId(id: Long?) { scope.launch { settingsRepo.setDefaultLayerId(id) } }
    fun setAutoCreateLayer(enabled: Boolean) { scope.launch { settingsRepo.setAutoCreateLayer(enabled) } }
    fun setAttachPhotoOnScan(enabled: Boolean) { scope.launch { settingsRepo.setAttachPhotoOnScan(enabled) } }
    fun setScanLlmLevel(level: String) { scope.launch { settingsRepo.setScanLlmLevel(level) } }
    fun setDebugLoggingEnabled(enabled: Boolean) {
        Logger.setEnabled(enabled)
        scope.launch { settingsRepo.setDebugLoggingEnabled(enabled) }
    }
    fun setDebugToastsEnabled(enabled: Boolean) {
        Logger.setToastsEnabled(enabled)
        scope.launch { settingsRepo.setDebugToastsEnabled(enabled) }
    }
    fun setThemeDarkMode(mode: String) { scope.launch { settingsRepo.setThemeDarkMode(mode) } }
    fun setThemeColored(colored: Boolean) { scope.launch { settingsRepo.setThemeColored(colored) } }

    fun setShowEventDetailsInWidget(enabled: Boolean) { scope.launch { settingsRepo.setShowEventDetailsInWidget(enabled) } }

    fun setWidgetBorderEnabled(enabled: Boolean) { scope.launch { settingsRepo.setWidgetBorderEnabled(enabled) } }

    fun setWidgetBorderThicknessDp(thicknessDp: Int) { scope.launch { settingsRepo.setWidgetBorderThicknessDp(thicknessDp) } }

    fun setWidgetBorderColorArgb(colorArgb: Long) { scope.launch { settingsRepo.setWidgetBorderColorArgb(colorArgb) } }
    fun setTodayEffect(effect: String) { scope.launch { settingsRepo.setTodayEffect(effect) } }
    fun setTodayEffectStyle(style: String) { scope.launch { settingsRepo.setTodayEffectStyle(style) } }
    fun setTodayEffectColor(colorArgb: Long) { scope.launch { settingsRepo.setTodayEffectColor(colorArgb) } }
    fun setTodayEffectColor2(colorArgb: Long?) { scope.launch { settingsRepo.setTodayEffectColor2(colorArgb) } }
    fun setTodayEffectSpeed(speed: Float) { scope.launch { settingsRepo.setTodayEffectSpeed(speed) } }
    fun setTodayEffectShowInWidget(enabled: Boolean) { scope.launch { settingsRepo.setTodayEffectShowInWidget(enabled) } }
    fun setIsGridView(enabled: Boolean) { scope.launch { settingsRepo.setIsGridView(enabled) } }
    fun setTodoBleedToCalendar(enabled: Boolean) { scope.launch { settingsRepo.setTodoBleedToCalendar(enabled) } }
    fun setAnimationsEnabled(enabled: Boolean) { scope.launch { settingsRepo.setAnimationsEnabled(enabled) } }

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
            // No channel rotation needed for volume since we bypass it via MediaPlayer
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

    // --- SESSION LOCK ---
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

    // --- ENTRY CRUD (delegated) ---
    fun addEntry(
        type: CalendarEntryType,
        title: String,
        description: String?,
        location: String?,
        startMillis: Long,
        endMillis: Long?,
        allDay: Boolean,
        completed: Boolean,
        isImportant: Boolean = false,
        recurrenceFrequency: RecurrenceFrequency,
        recurrenceInterval: Int = 1,
        recurrenceUntilMillis: Long?,
        recurrenceDaysMask: Int = 0,
        layerId: Long,
        tags: List<String>,
        reminderOffsetsMinutes: List<Int> = emptyList(),
        onResult: (Long) -> Unit = {}
    ) {
        scope.launch {
            onResult(
                calendarRepo.addEntry(
                    type = type,
                    title = title,
                    description = description,
                    location = location,
                    startMillis = startMillis,
                    endMillis = endMillis,
                    allDay = allDay,
                    completed = completed,
                    isImportant = isImportant,
                    recurrenceFrequency = recurrenceFrequency,
                    recurrenceInterval = recurrenceInterval,
                    recurrenceUntilMillis = recurrenceUntilMillis,
                    recurrenceDaysMask = recurrenceDaysMask,
                    layerId = layerId,
                    tags = tags,
                    reminderOffsetsMinutes = reminderOffsetsMinutes
                )
            )
        }
    }

    fun updateEntry(entry: CalendarEntry, tags: List<String>, reminderOffsetsMinutes: List<Int> = emptyList()) {
        scope.launch { calendarRepo.updateEntry(entry, tags, reminderOffsetsMinutes) }
    }

    /** Gate lives here (not in the memory) — mirrors vox-expenses' recordFieldCorrections
     *  convention. Tags are deliberately absent: tag edits are set add/remove operations, and
     *  pairing two tag lists positionally would teach from unrelated pairs. */
    fun recordFieldCorrections(old: CalendarEntry, new: CalendarEntry) {
        if (!settingsRepo.getSnapshot().fieldCorrectionMemoryEnabled) return
        scope.launch {
            fieldCorrectionMemory.learn(
                listOf(old.title, old.description, old.location),
                listOf(new.title, new.description, new.location)
            )
        }
    }

    fun setFieldCorrectionMemoryEnabled(enabled: Boolean) = scope.launch { settingsRepo.setFieldCorrectionMemoryEnabled(enabled) }
    fun setFieldCorrectionThreshold(count: Int) = scope.launch { settingsRepo.setFieldCorrectionThreshold(count) }

    fun deleteEntry(entry: CalendarEntry) { scope.launch { calendarRepo.deleteEntry(entry) } }

    // --- Multi-select batch actions (Day/Week view) ---

    fun bulkDeleteEntries(ids: List<Long>) { scope.launch { calendarRepo.bulkDeleteEntries(ids) } }

    fun bulkMoveEntries(ids: List<Long>, newLayerId: Long) { scope.launch { calendarRepo.bulkMoveEntries(ids, newLayerId) } }

    fun createLayerAndMoveEntries(name: String, colorArgb: Long, entryIds: List<Long>) {
        val position = uiStateLayers().size
        scope.launch {
            val newId = calendarRepo.addLayer(name, colorArgb, position)
            if (newId > 0) calendarRepo.bulkMoveEntries(entryIds, newId)
        }
    }

    // --- LAYER CRUD (delegated) ---
    fun addLayer(name: String, colorArgb: Long) {
        val position = uiStateLayers().size
        scope.launch { calendarRepo.addLayer(name, colorArgb, position) }
    }

    fun updateLayer(layer: CalendarLayer) { scope.launch { calendarRepo.updateLayer(layer) } }

    fun setMainLayer(layerId: Long) { scope.launch { calendarRepo.setMainLayer(layerId) } }

    fun reorderLayers(orderedIds: List<Long>) { scope.launch { calendarRepo.reorderLayers(orderedIds) } }

    fun removeLayer(layer: CalendarLayer, mode: CalendarRepository.LayerDeleteMode) {
        if (layer.isDefault) return // the Main calendar can't be deleted — nothing left to reassign to
        scope.launch { calendarRepo.deleteLayer(layer, mode) }
    }

    /** Turns a calendar's reminder override on/off (or edits its offsets) and immediately reschedules
     *  every entry currently under it — see [CalendarRepository.setLayerReminderOffsets]. */
    fun setLayerReminderOffsets(layerId: Long, offsets: List<Int>) {
        scope.launch { calendarRepo.setLayerReminderOffsets(layerId, offsets) }
    }

    // --- Online-subscribed calendars ---

    fun addSubscribedLayer(name: String, colorArgb: Long, url: String) {
        val position = uiStateLayers().size
        scope.launch {
            val id = calendarRepo.addSubscribedLayer(name, colorArgb, position, url)
            if (id > 0) {
                val layer = calendarRepo.layersSnapshot().firstOrNull { it.id == id } ?: return@launch
                CalendarSubscriptionSyncEngine.sync(calendarRepo, layer, IcsUrlFetcher::fetch)
            }
        }
    }

    fun resyncSubscribedLayer(layer: CalendarLayer) {
        scope.launch { CalendarSubscriptionSyncEngine.sync(calendarRepo, layer, IcsUrlFetcher::fetch) }
    }

    fun duplicateLayerToOfflineCopy(source: CalendarLayer, newName: String) {
        scope.launch { calendarRepo.duplicateLayerToOfflineCopy(source, newName) }
    }

    // --- Attachments (manually-added photos on an entry — see :core:attachments) ---
    fun observeAttachments(entryId: Long): Flow<List<AttachmentEntity>> =
        attachmentDao.observeFor(CalendarAttachments.RECORD_TYPE, entryId)

    fun addManualAttachment(entryId: Long, fileName: String, groupId: String? = null, groupOrder: Int = 0) {
        scope.launch {
            attachmentDao.insert(
                AttachmentEntity(
                    recordType = CalendarAttachments.RECORD_TYPE,
                    recordId = entryId,
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
            AttachmentFileStore.delete(context, CalendarAttachments.DIR, entity.fileName)
        }
    }

    /** Cancels a burst mid-capture (see [com.voxapps.attachments.ui.rememberBurstCaptureLauncher]) —
     *  deletes every row+file already committed under [groupId] for this entry. */
    fun deleteAttachmentGroup(entryId: Long, groupId: String, context: Context) {
        scope.launch {
            val deleted = attachmentDao.deleteGroup(CalendarAttachments.RECORD_TYPE, entryId, groupId)
            deleted.forEach { AttachmentFileStore.delete(context, CalendarAttachments.DIR, it.fileName) }
        }
    }

    // --- Reminders (see domain/reminders/ReminderScheduler; non-recurring entries only, v1) ---

    /** One-shot fetch for EntryEditScreen to seed its local reminder-selection state on open —
     *  reminders aren't observed reactively anywhere else, unlike attachments. */
    suspend fun getRemindersForEntry(entryId: Long): List<CalendarReminder> =
        calendarRepo.getRemindersForEntry(entryId)

    private fun uiStateLayers(): List<CalendarLayer> =
        (_uiState.value as? CalendarUiState.Unlocked)?.layers ?: emptyList()
}
