package com.voxapps.calendarapp.domain.llm

import com.voxapps.calendarapp.data.CalendarEntryType
import com.voxapps.datahygiene.optCleanString
import com.voxapps.schema.VoxExtractionSchema
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException

/** What kind of record a parsed result should become — a calendar Event, a calendar Task (both stored
 *  as a plain [CalendarEntryType]-typed [com.voxapps.calendarapp.data.CalendarEntry]), or a to-do
 *  checklist item (stored via [com.voxapps.calendarapp.data.ToDoRepository], never as its own
 *  [CalendarEntryType] value — see [CalendarEventParseResultParser.Parsed.calendarType]). */
enum class ParsedKind { EVENT, TASK, TODO }

/**
 * Parses Commander's structured reply to a [CalendarEventParsePromptBuilder] request. `title` is
 * always mandatory; a resolvable `startDate` is mandatory too UNLESS [ParsedKind.TODO] — a to-do
 * checklist item frequently has no due date at all (mirrors vox-expenses' `ExpenseParseResultParser`'s
 * single-mandatory-field convention, extended with this one kind-conditional exception). [parse]
 * returns null (discard, don't create a broken record) whenever a mandatory field is missing or
 * unparseable.
 */
object CalendarEventParseResultParser {
    @VoxExtractionSchema(version = 2)
    data class Parsed(
        val title: String,
        val kind: ParsedKind,
        val startMillis: Long?,
        val endMillis: Long?,
        val allDay: Boolean,
        val layer: String?,
        val listName: String?,
        val tags: List<String>
    ) {
        /** EVENT/TASK are always stored as a plain [CalendarEntryType]-typed entry — never called for
         *  [ParsedKind.TODO], which routes to [com.voxapps.calendarapp.data.ToDoRepository] instead. */
        val calendarType: CalendarEntryType get() = if (kind == ParsedKind.TASK) CalendarEntryType.TASK else CalendarEntryType.EVENT
    }

    fun parse(json: String, zoneId: ZoneId = ZoneId.systemDefault()): Parsed? = try {
        val o = JSONObject(json)
        val title = o.optCleanString("title") ?: return null
        val kind = when (o.optString("kind").uppercase()) {
            "TODO" -> ParsedKind.TODO
            "TASK" -> ParsedKind.TASK
            else -> ParsedKind.EVENT
        }

        val startDate = o.optCleanString("startDate")?.let(::parseDate)
        if (kind != ParsedKind.TODO && startDate == null) return null // date still mandatory for EVENT/TASK

        val allDay = o.optBoolean("allDay", true)
        val startTime = if (allDay) null else o.optCleanString("startTime")?.let(::parseTime)
        val startMillis = startDate?.let { toMillis(it, startTime, zoneId) }

        val endMillis = o.optCleanString("endDate")?.let(::parseDate)?.let { endDate ->
            val endTime = if (allDay) null else o.optCleanString("endTime")?.let(::parseTime)
            toMillis(endDate, endTime, zoneId)
        }

        val tagsArray = o.optJSONArray("tags") ?: JSONArray()
        val tags = (0 until tagsArray.length()).mapNotNull { i -> tagsArray.optString(i).takeIf { it.isNotBlank() && it != "null" } }

        Parsed(
            title = title,
            kind = kind,
            startMillis = startMillis,
            endMillis = endMillis,
            allDay = allDay,
            layer = o.optCleanString("layer"),
            listName = o.optCleanString("listName"),
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
