package com.voxapps.calendarapp.receiver

import com.voxapps.calendarapp.data.CalendarRepository
import com.voxapps.calendarapp.data.preferences.CalendarSettingsRepository
import com.voxapps.calendarapp.state.SessionManager
import com.voxapps.ipc.VoxResult

/**
 * The read-command decision, extracted from the BroadcastReceiver so it's unit-testable without
 * Android (mirrors vox-expenses' ExpensesReadResponder). When biometric is required and the session
 * has expired it returns a locked message and **never touches the DB**; otherwise it snapshots entries.
 */
class CalendarReadResponder(
    private val settingsRepo: CalendarSettingsRepository,
    private val sessionManager: SessionManager,
    private val calendarRepo: CalendarRepository,
    private val lockedMessage: String
) {
    suspend fun respond(): VoxResult {
        val settings = settingsRepo.getSnapshot()
        val locked = settings.isBiometricRequired &&
            !sessionManager.isSessionValid(settings.sessionTimeoutMinutes)
        if (locked) return VoxResult(ok = false, text = lockedMessage)

        val text = calendarRepo.entriesSnapshot().joinToString("\n") { ewt ->
            "${ewt.entry.title}: ${ewt.entry.startMillis}"
        }
        return VoxResult(ok = true, text = text)
    }
}
