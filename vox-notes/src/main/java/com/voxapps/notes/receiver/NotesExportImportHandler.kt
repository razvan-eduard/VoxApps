package com.voxapps.notes.receiver

import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxResult
import com.voxapps.notes.data.Category
import com.voxapps.notes.data.NotesRepository
import com.voxapps.notes.data.preferences.NotesSettings
import com.voxapps.notes.data.preferences.NotesSettingsRepository
import com.voxapps.notes.state.SessionManager
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/**
 * Vox Hub's export/import for this app, extracted from the BroadcastReceiver so it's unit-testable
 * without Android (mirrors [NotesReadResponder]). Respects the same biometric-lock gate as reads —
 * an export/import request while the app is locked never touches the DB.
 */
class NotesExportImportHandler(
    private val settingsRepo: NotesSettingsRepository,
    private val sessionManager: SessionManager,
    private val notesRepo: NotesRepository
) {
    suspend fun export(scope: String = VoxIpc.EXPORT_SCOPE_BOTH): VoxResult {
        val settings = settingsRepo.getSnapshot()
        val locked = settings.isBiometricRequired &&
            !sessionManager.isSessionValid(settings.sessionTimeoutMinutes)
        if (locked) return VoxResult(ok = false, text = NotesReadResponder.LOCKED_MESSAGE)

        val json = JSONObject()
        if (scope != VoxIpc.EXPORT_SCOPE_DATA) {
            json.put("settings", settings.toJson())
        }
        if (scope != VoxIpc.EXPORT_SCOPE_SETTINGS) {
            val categories = notesRepo.categories.first()
            val notes = notesRepo.notesSnapshot()
            json.put("categories", JSONArray(categories.map { it.toJson() }))
            json.put(
                "notes",
                JSONArray(
                    notes.map { note ->
                        JSONObject().apply {
                            put("id", note.id)
                            put("title", note.title)
                            put("text", note.text)
                            put("createdAt", note.createdAt)
                            put("categoryId", note.categoryId)
                        }
                    }
                )
            )
        }
        return VoxResult(ok = true, text = json.toString())
    }

    suspend fun import(payloadJson: String): VoxResult {
        val settings = settingsRepo.getSnapshot()
        val locked = settings.isBiometricRequired &&
            !sessionManager.isSessionValid(settings.sessionTimeoutMinutes)
        if (locked) return VoxResult(ok = false, text = NotesReadResponder.LOCKED_MESSAGE)

        val root = try {
            JSONObject(payloadJson)
        } catch (e: Exception) {
            return VoxResult(ok = false, text = "Invalid import payload")
        }

        root.optJSONObject("settings")?.let { settingsRepo.restoreSettings(it.toNotesSettings()) }

        val existingCategories = notesRepo.categories.first()
        val nameToId = existingCategories.associate { it.name.lowercase() to it.id }.toMutableMap()
        val importedIdToLocalId = mutableMapOf<Long, Long?>()
        val importedCategories = root.optJSONArray("categories") ?: JSONArray()
        var categoriesCreated = 0
        for (i in 0 until importedCategories.length()) {
            val c = importedCategories.getJSONObject(i)
            val name = c.optString("name").trim()
            if (name.isEmpty()) continue
            val importedId = c.optLong("id")
            val localId = nameToId[name.lowercase()] ?: run {
                val newId = notesRepo.addCategory(
                    name,
                    c.optLong("colorArgb"),
                    c.optInt("position"),
                    c.optLong("createdAt", System.currentTimeMillis())
                )
                if (newId > 0) {
                    categoriesCreated++
                    nameToId[name.lowercase()] = newId
                }
                newId.takeIf { it > 0 }
            }
            importedIdToLocalId[importedId] = localId
        }

        var notesCreated = 0
        if (root.has("notes")) {
            // Replace, not merge: importing a notes payload is a restore of that snapshot, not a
            // merge with whatever's already on this device — so instead of per-note duplicate
            // detection, snapshot the pre-existing note ids, insert every imported note, then
            // delete exactly the ids that existed before the import. Categories are untouched here
            // (they already merge safely by name above).
            val preExistingNoteIds = notesRepo.notesSnapshot().map { it.id }

            val importedNotes = root.optJSONArray("notes") ?: JSONArray()
            for (i in 0 until importedNotes.length()) {
                val n = importedNotes.getJSONObject(i)
                val text = n.optString("text")
                val title = n.optStringOrNull("title")
                if (text.isBlank() && title.isNullOrBlank()) continue
                val importedCategoryId = if (n.has("categoryId") && !n.isNull("categoryId")) n.optLong("categoryId") else null
                val categoryId = importedCategoryId?.let { importedIdToLocalId[it] }
                notesRepo.addNote(
                    title = title,
                    text = text,
                    categoryId = categoryId,
                    createdAt = n.optLong("createdAt", System.currentTimeMillis())
                )
                notesCreated++
            }

            preExistingNoteIds.forEach { notesRepo.deleteNoteById(it) }
        }

        return VoxResult(
            ok = true,
            text = "$notesCreated notes imported, $categoriesCreated new categories " +
                "(${importedCategories.length() - categoriesCreated} matched existing)"
        )
    }
}

private fun NotesSettings.toJson(): JSONObject = JSONObject().apply {
    put("isBiometricRequired", isBiometricRequired)
    put("sessionTimeoutMinutes", sessionTimeoutMinutes)
    put("defaultVoiceCategoryId", defaultVoiceCategoryId)
    put("voiceSaveToastEnabled", voiceSaveToastEnabled)
    put("autoCreateVoiceCategory", autoCreateVoiceCategory)
    put("language", language)
    put("scheduledMergeInterval", scheduledMergeInterval)
    put("scheduledNoteDedupInterval", scheduledNoteDedupInterval)
    put("debugLoggingEnabled", debugLoggingEnabled)
    put("calendarViewEnabled", calendarViewEnabled)
    put("themeDarkMode", themeDarkMode)
    put("themeColored", themeColored)
}

private fun JSONObject.toNotesSettings(): NotesSettings = NotesSettings(
    isBiometricRequired = optBoolean("isBiometricRequired", false),
    sessionTimeoutMinutes = optInt("sessionTimeoutMinutes", NotesSettings.TIMEOUT_30M),
    defaultVoiceCategoryId = if (has("defaultVoiceCategoryId") && !isNull("defaultVoiceCategoryId")) optLong("defaultVoiceCategoryId") else null,
    voiceSaveToastEnabled = optBoolean("voiceSaveToastEnabled", false),
    autoCreateVoiceCategory = optBoolean("autoCreateVoiceCategory", false),
    language = optString("language", NotesSettings.DEFAULT_LANGUAGE),
    scheduledMergeInterval = optString("scheduledMergeInterval", NotesSettings.INTERVAL_OFF),
    scheduledNoteDedupInterval = optString("scheduledNoteDedupInterval", NotesSettings.INTERVAL_OFF),
    debugLoggingEnabled = optBoolean("debugLoggingEnabled", false),
    calendarViewEnabled = optBoolean("calendarViewEnabled", false),
    themeDarkMode = optString("themeDarkMode", NotesSettings.THEME_SYSTEM),
    themeColored = optBoolean("themeColored", true)
)

private fun Category.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("colorArgb", colorArgb)
    put("position", position)
    put("createdAt", createdAt)
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null
