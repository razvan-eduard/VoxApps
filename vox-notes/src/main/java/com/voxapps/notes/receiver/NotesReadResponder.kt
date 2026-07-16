package com.voxapps.notes.receiver

import com.voxapps.ipc.VoxResult
import com.voxapps.notes.data.NotesRepository
import com.voxapps.notes.data.preferences.NotesSettingsRepository
import com.voxapps.notes.state.SessionManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * The read-command decision, extracted from the BroadcastReceiver so it's unit-testable without
 * Android. When biometric is required and the session has expired it returns a locked message and
 * **never touches the DB**; otherwise it snapshots the notes.
 *
 * When [dateFrom]/[dateTo] are both given (Vox Calendar's day-tap summary), the reply is a compact
 * JSON `{"count": N, "items": [{"title", "timeMillis"}, ...]}` instead of the plain-text format, since
 * the caller needs to parse counts/titles programmatically. Commander's own "read my notes aloud" TTS
 * flow never sets these, so it keeps getting the original human-readable text unchanged.
 */
class NotesReadResponder(
    private val settingsRepo: NotesSettingsRepository,
    private val sessionManager: SessionManager,
    private val notesRepo: NotesRepository
) {
    suspend fun respond(dateFrom: Long? = null, dateTo: Long? = null): VoxResult {
        val settings = settingsRepo.getSnapshot()

        // Fix: If biometric is NOT required, the record is NEVER locked.
        val locked = settings.isBiometricRequired &&
            !sessionManager.isSessionValid(settings.sessionTimeoutMinutes)

        if (locked) return VoxResult(ok = false, text = LOCKED_MESSAGE)

        if (dateFrom != null && dateTo != null) {
            val notes = notesRepo.notesForDateRange(dateFrom, dateTo)
            val items = JSONArray()
            notes.forEach { note ->
                items.put(
                    JSONObject().apply {
                        put("title", note.title ?: note.text.take(60))
                        put("timeMillis", note.createdAt)
                    }
                )
            }
            val json = JSONObject().put("count", notes.size).put("items", items)
            return VoxResult(ok = true, text = json.toString())
        }

        val text = notesRepo.notesSnapshot().joinToString("\n") { note ->
            listOfNotNull(note.title, note.text).joinToString(": ")
        }
        return VoxResult(ok = true, text = text)
    }

    companion object {
        const val LOCKED_MESSAGE = "Notele sunt blocate. Deblochează aplicația."
    }
}
