package com.voxapps.notes.receiver

import com.voxapps.ipc.VoxResult
import com.voxapps.notes.data.NotesRepository
import com.voxapps.notes.data.preferences.NotesSettingsRepository
import com.voxapps.notes.state.SessionManager

/**
 * The read-command decision, extracted from the BroadcastReceiver so it's unit-testable without
 * Android. When biometric is required and the session has expired it returns a locked message and
 * **never touches the DB**; otherwise it snapshots the notes.
 */
class NotesReadResponder(
    private val settingsRepo: NotesSettingsRepository,
    private val sessionManager: SessionManager,
    private val notesRepo: NotesRepository
) {
    suspend fun respond(): VoxResult {
        val settings = settingsRepo.getSnapshot()
        val locked = settings.isBiometricRequired &&
            !sessionManager.isSessionValid(settings.sessionTimeoutMinutes)
        if (locked) return VoxResult(ok = false, text = LOCKED_MESSAGE)

        val text = notesRepo.notesSnapshot().joinToString("\n") { note ->
            listOfNotNull(note.title, note.text).joinToString(": ")
        }
        return VoxResult(ok = true, text = text)
    }

    companion object {
        const val LOCKED_MESSAGE = "Notele sunt blocate. Deblochează aplicația."
    }
}
