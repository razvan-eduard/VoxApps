package com.voxapps.calendarapp.receiver

import com.voxapps.calendarapp.data.CalendarEntry
import com.voxapps.calendarapp.data.CalendarEntryType
import com.voxapps.calendarapp.data.CalendarEntryWithTags
import com.voxapps.calendarapp.data.CalendarLayer
import com.voxapps.calendarapp.data.CalendarRepository
import com.voxapps.calendarapp.data.RecurrenceFrequency
import com.voxapps.calendarapp.data.preferences.CalendarSettings
import com.voxapps.calendarapp.data.preferences.CalendarSettingsRepository
import com.voxapps.calendarapp.state.SessionManager
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxResult
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Vox Hub's export/import for this app, extracted from the BroadcastReceiver so it's unit-testable
 * without Android (mirrors vox-expenses' `ExpensesExportImportHandler`). Respects the same
 * biometric-lock gate as reads — an export/import request while the app is locked never touches the
 * DB. Fully independent of the ICS import/export screen (Phase 5) — different format, different path.
 */
class CalendarExportImportHandler(
    private val settingsRepo: CalendarSettingsRepository,
    private val sessionManager: SessionManager,
    private val calendarRepo: CalendarRepository
) {
    suspend fun export(scope: String = VoxIpc.EXPORT_SCOPE_BOTH): VoxResult {
        val settings = settingsRepo.getSnapshot()
        val locked = settings.isBiometricRequired &&
            !sessionManager.isSessionValid(settings.sessionTimeoutMinutes)
        if (locked) return VoxResult(ok = false, text = CalendarReadResponder.LOCKED_MESSAGE)

        val json = JSONObject()
        if (scope != VoxIpc.EXPORT_SCOPE_DATA) {
            json.put("settings", settings.toJson())
        }
        if (scope != VoxIpc.EXPORT_SCOPE_SETTINGS) {
            val layers = calendarRepo.layersSnapshot()
            val entries = calendarRepo.entriesSnapshot()
            json.put("layers", JSONArray(layers.map { it.toJson() }))
            json.put("events", JSONArray(entries.map { it.toJson() }))
        }
        return VoxResult(ok = true, text = json.toString())
    }

    suspend fun import(payloadJson: String): VoxResult {
        val settings = settingsRepo.getSnapshot()
        val locked = settings.isBiometricRequired &&
            !sessionManager.isSessionValid(settings.sessionTimeoutMinutes)
        if (locked) return VoxResult(ok = false, text = CalendarReadResponder.LOCKED_MESSAGE)

        val root = try {
            JSONObject(payloadJson)
        } catch (e: Exception) {
            return VoxResult(ok = false, text = "Invalid import payload")
        }

        root.optJSONObject("settings")?.let { settingsRepo.restoreSettings(it.toCalendarSettings()) }

        // Layers merge by name (mirrors categories in vox-expenses) — entries reference layers by id,
        // so wholesale-replacing layers would orphan any untouched records pointing at old layer ids.
        val existingLayers = calendarRepo.layersSnapshot()
        val nameToId = existingLayers.associate { it.name.lowercase() to it.id }.toMutableMap()
        val importedIdToLocalId = mutableMapOf<Long, Long?>()
        val defaultLocalLayerId = existingLayers.firstOrNull { it.isDefault }?.id ?: existingLayers.firstOrNull()?.id
        val importedLayers = root.optJSONArray("layers") ?: JSONArray()
        var layersCreated = 0
        for (i in 0 until importedLayers.length()) {
            val l = importedLayers.getJSONObject(i)
            val name = l.optString("name").trim()
            if (name.isEmpty()) continue
            val importedId = l.optLong("id")
            val localId = nameToId[name.lowercase()] ?: run {
                val newId = calendarRepo.addLayer(
                    name = name,
                    colorArgb = l.optLong("colorArgb"),
                    position = l.optInt("position")
                )
                if (newId > 0) {
                    layersCreated++
                    nameToId[name.lowercase()] = newId
                }
                newId.takeIf { it > 0 }
            }
            importedIdToLocalId[importedId] = localId
        }

        // Entries use snapshot-then-replace semantics (mirrors vox-expenses' expenses): snapshot
        // pre-existing entries, insert every imported one, then delete exactly what existed before.
        var entriesCreated = 0
        if (root.has("events")) {
            val preExistingIds = calendarRepo.entriesSnapshot().map { it.entry.id }

            val importedEntries = root.optJSONArray("events") ?: JSONArray()
            for (i in 0 until importedEntries.length()) {
                val e = importedEntries.getJSONObject(i)
                val importedLayerId = e.optLong("layerId")
                val layerId = importedIdToLocalId[importedLayerId] ?: defaultLocalLayerId ?: continue
                val tagsArray = e.optJSONArray("tags") ?: JSONArray()
                val tags = (0 until tagsArray.length()).map { tagsArray.optString(it) }

                calendarRepo.addEntry(
                    uid = e.optStringOrNull("uid") ?: UUID.randomUUID().toString(),
                    type = e.optStringOrNull("type")?.let { runCatching { CalendarEntryType.valueOf(it) }.getOrNull() }
                        ?: CalendarEntryType.EVENT,
                    title = e.optString("title"),
                    description = e.optStringOrNull("description"),
                    location = e.optStringOrNull("location"),
                    startMillis = e.optLong("startMillis"),
                    endMillis = if (e.has("endMillis") && !e.isNull("endMillis")) e.optLong("endMillis") else null,
                    allDay = e.optBoolean("allDay", false),
                    completed = e.optBoolean("completed", false),
                    recurrenceFrequency = e.optStringOrNull("recurrenceFrequency")
                        ?.let { runCatching { RecurrenceFrequency.valueOf(it) }.getOrNull() }
                        ?: RecurrenceFrequency.NONE,
                    recurrenceUntilMillis = if (e.has("recurrenceUntilMillis") && !e.isNull("recurrenceUntilMillis")) {
                        e.optLong("recurrenceUntilMillis")
                    } else {
                        null
                    },
                    layerId = layerId,
                    tags = tags
                )
                entriesCreated++
            }

            preExistingIds.forEach { calendarRepo.deleteEntryById(it) }
        }

        return VoxResult(
            ok = true,
            text = "$entriesCreated entries imported, $layersCreated new calendars " +
                "(${importedLayers.length() - layersCreated} matched existing)"
        )
    }
}

private fun CalendarSettings.toJson(): JSONObject = JSONObject().apply {
    put("isBiometricRequired", isBiometricRequired)
    put("sessionTimeoutMinutes", sessionTimeoutMinutes)
    put("language", language)
    put("defaultLayerId", defaultLayerId)
    put("autoCreateLayer", autoCreateLayer)
    put("debugLoggingEnabled", debugLoggingEnabled)
    put("themeDarkMode", themeDarkMode)
    put("themeColored", themeColored)
}

private fun JSONObject.toCalendarSettings(): CalendarSettings = CalendarSettings(
    isBiometricRequired = optBoolean("isBiometricRequired", false),
    sessionTimeoutMinutes = optInt("sessionTimeoutMinutes", CalendarSettings.TIMEOUT_30M),
    language = optString("language", CalendarSettings.DEFAULT_LANGUAGE),
    defaultLayerId = if (has("defaultLayerId") && !isNull("defaultLayerId")) optLong("defaultLayerId") else null,
    autoCreateLayer = optBoolean("autoCreateLayer", false),
    debugLoggingEnabled = optBoolean("debugLoggingEnabled", false),
    themeDarkMode = optString("themeDarkMode", CalendarSettings.THEME_SYSTEM),
    themeColored = optBoolean("themeColored", true)
)

private fun CalendarLayer.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("colorArgb", colorArgb)
    put("visible", visible)
    put("isDefault", isDefault)
    put("position", position)
    put("createdAt", createdAt)
}

private fun CalendarEntryWithTags.toJson(): JSONObject = JSONObject().apply {
    val e: CalendarEntry = entry
    put("id", e.id)
    put("uid", e.uid)
    put("type", e.type.name)
    put("title", e.title)
    put("description", e.description)
    put("location", e.location)
    put("startMillis", e.startMillis)
    put("endMillis", e.endMillis)
    put("allDay", e.allDay)
    put("completed", e.completed)
    put("recurrenceFrequency", e.recurrenceFrequency.name)
    put("recurrenceUntilMillis", e.recurrenceUntilMillis)
    put("layerId", e.layerId)
    put("tags", JSONArray(tagNames))
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null
