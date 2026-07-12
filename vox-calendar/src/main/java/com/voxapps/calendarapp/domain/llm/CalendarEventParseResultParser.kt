package com.voxapps.calendarapp.domain.llm

import com.voxapps.calendarapp.data.CalendarEntryType
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException

/**
 * Parses Commander's structured reply to a [CalendarEventParsePromptBuilder] request. `title` and a
 * resolvable `startDate` are the only truly mandatory fields (mirrors vox-expenses'
 * `ExpenseParseResultParser`'s single-mandatory-field convention) — [parse] returns null (discard,
 * don't create a broken entry) if either is missing or unparseable.
 */
object CalendarEventParseResultParser {
    data class Parsed(
        val title: String,
        val type: CalendarEntryType,
        val startMillis: Long,
        val endMillis: Long?,
        val allDay: Boolean,
        val layer: String?,
        val tags: List<String>
    )

    fun parse(json: String, zoneId: ZoneId = ZoneId.systemDefault()): Parsed? = try {
        val o = JSONObject(json)
        val title = o.optString("title").takeIf { it.isNotBlank() } ?: return null
        val startDate = o.optString("startDate").takeIf { it.isNotBlank() }?.let(::parseDate) ?: return null
        val allDay = o.optBoolean("allDay", true)
        val startTime = if (allDay) null else o.optString("startTime").takeIf { it.isNotBlank() }?.let(::parseTime)
        val startMillis = toMillis(startDate, startTime, zoneId)

        val endMillis = o.optString("endDate").takeIf { it.isNotBlank() }?.let(::parseDate)?.let { endDate ->
            val endTime = if (allDay) null else o.optString("endTime").takeIf { it.isNotBlank() }?.let(::parseTime)
            toMillis(endDate, endTime, zoneId)
        }

        val type = if (o.optString("type").uppercase() == "TASK") CalendarEntryType.TASK else CalendarEntryType.EVENT

        val tagsArray = o.optJSONArray("tags") ?: JSONArray()
        val tags = (0 until tagsArray.length()).mapNotNull { i -> tagsArray.optString(i).takeIf { it.isNotBlank() } }

        Parsed(
            title = title,
            type = type,
            startMillis = startMillis,
            endMillis = endMillis,
            allDay = allDay,
            layer = o.optString("layer").takeIf { it.isNotBlank() },
            tags = tags
        )
    } catch (e: Exception) {
        null
    }

    private fun parseDate(s: String): LocalDate? = try { LocalDate.parse(s) } catch (e: DateTimeParseException) { null }
    private fun parseTime(s: String): LocalTime? = try { LocalTime.parse(s) } catch (e: DateTimeParseException) { null }

    private fun toMillis(date: LocalDate, time: LocalTime?, zoneId: ZoneId): Long =
        ZonedDateTime.of(date, time ?: LocalTime.MIDNIGHT, zoneId).toInstant().toEpochMilli()
}
