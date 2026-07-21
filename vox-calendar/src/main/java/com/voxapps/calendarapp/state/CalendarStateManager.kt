package com.voxapps.calendarapp.state

import com.voxapps.calendarapp.data.CalendarEntry
import com.voxapps.calendarapp.data.CalendarEntryType
import com.voxapps.calendarapp.data.CalendarLayer
import com.voxapps.calendarapp.data.CalendarLayerPalette
import com.voxapps.calendarapp.data.CalendarRepository
import com.voxapps.calendarapp.data.RecurrenceFrequency
import com.voxapps.calendarapp.data.preferences.CalendarSettingsRepository
import com.voxapps.logging.Logger
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
    private val sessionManager: SessionManager
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
        tags: List<String>
    ) {
        scope.launch {
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
                tags = tags
            )
        }
    }

    fun updateEntry(entry: CalendarEntry, tags: List<String>) {
        scope.launch { calendarRepo.updateEntry(entry, tags) }
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

    private fun uiStateLayers(): List<CalendarLayer> =
        (_uiState.value as? CalendarUiState.Unlocked)?.layers ?: emptyList()

    companion object {
        @Volatile private var instance: CalendarStateManager? = null

        fun getInstance(
            settingsRepo: CalendarSettingsRepository,
            calendarRepo: CalendarRepository,
            sessionManager: SessionManager
        ): CalendarStateManager = instance ?: synchronized(this) {
            instance ?: CalendarStateManager(settingsRepo, calendarRepo, sessionManager).also { instance = it }
        }
    }
}
