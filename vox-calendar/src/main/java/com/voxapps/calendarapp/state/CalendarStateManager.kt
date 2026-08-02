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
import com.voxapps.calendarapp.data.CalendarLayerPalette
import com.voxapps.calendarapp.data.CalendarReminder
import com.voxapps.calendarapp.data.CalendarRepository
import com.voxapps.calendarapp.data.RecurrenceFrequency
import com.voxapps.calendarapp.data.preferences.CalendarSettingsRepository
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

/**
 * Reactive state hub for Vox Calendar (mirrors vox-expenses' ExpensesStateManager). Combines persisted
 * settings + Room flows + ephemeral runtime (view mode, filters, session tick) into a single [uiState]
 * that is either [CalendarUiState.Locked] or [CalendarUiState.Unlocked]. Persistence is delegated to
 * [CalendarRepository]; the biometric session lives in [SessionManager].
 *
 * Seeds a single default layer ("Personal") on first-ever launch (empty layer table) so entries always
 * have somewhere to land — [CalendarEntry.layerId] is non-nullable, unlike Notes'/Expenses' category ids.
 */
class CalendarStateManager internal constructor(
    private val settingsRepo: CalendarSettingsRepository,
    private val calendarRepo: CalendarRepository,
    private val sessionManager: SessionManager,
    private val attachmentDao: AttachmentDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private data class Runtime(
        val selectedTags: Set<String> = emptySet(),
        val viewMode: CalendarViewMode = CalendarViewMode.MONTH,
        val selectedDateMillis: Long = System.currentTimeMillis(),
        val sessionTick: Int = 0
    )

    private val _runtime = MutableStateFlow(Runtime())

    private val _uiState = MutableStateFlow<CalendarUiState>(CalendarUiState.Loading)
    val uiState: StateFlow<CalendarUiState> = _uiState.asStateFlow()

    init {
        scope.launch { seedDefaultLayerIfNeeded() }

        combine(
            settingsRepo.settingsFlow,
            calendarRepo.entriesWithTags,
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
        }.onEach { _uiState.value = it }.launchIn(scope)
    }

    private suspend fun seedDefaultLayerIfNeeded() {
        if (calendarRepo.layersSnapshot().isEmpty()) {
            calendarRepo.addLayer(
                name = "Personal",
                colorArgb = CalendarLayerPalette.argb.first(),
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
        recurrenceFrequency: RecurrenceFrequency,
        recurrenceUntilMillis: Long?,
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
                    recurrenceFrequency = recurrenceFrequency,
                    recurrenceUntilMillis = recurrenceUntilMillis,
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

    fun deleteEntry(entry: CalendarEntry) { scope.launch { calendarRepo.deleteEntry(entry) } }

    // --- LAYER CRUD (delegated) ---
    fun addLayer(name: String, colorArgb: Long) {
        val position = uiStateLayers().size
        scope.launch { calendarRepo.addLayer(name, colorArgb, position) }
    }

    fun updateLayer(layer: CalendarLayer) { scope.launch { calendarRepo.updateLayer(layer) } }

    fun removeLayer(layer: CalendarLayer) {
        if (layer.isDefault) return // the default layer can't be deleted — nothing left to reassign to
        scope.launch { calendarRepo.deleteLayer(layer) }
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

    companion object {
        @Volatile private var instance: CalendarStateManager? = null

        fun getInstance(
            settingsRepo: CalendarSettingsRepository,
            calendarRepo: CalendarRepository,
            sessionManager: SessionManager,
            attachmentDao: AttachmentDao
        ): CalendarStateManager = instance ?: synchronized(this) {
            instance ?: CalendarStateManager(settingsRepo, calendarRepo, sessionManager, attachmentDao).also { instance = it }
        }
    }
}
