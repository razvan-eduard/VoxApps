package com.voxapps.calendarapp.domain.ics

import biweekly.Biweekly
import biweekly.ICalendar
import biweekly.component.VEvent
import biweekly.component.VTodo
import biweekly.property.Status
import biweekly.util.DayOfWeek
import biweekly.util.Frequency
import biweekly.util.ICalDate
import biweekly.util.Recurrence
import com.voxapps.calendarapp.data.CalendarEntry
import com.voxapps.calendarapp.data.CalendarEntryType
import com.voxapps.calendarapp.data.CalendarEntryWithTags
import com.voxapps.calendarapp.data.CalendarLayer
import com.voxapps.calendarapp.data.CalendarRepository
import com.voxapps.calendarapp.data.RecurrenceFrequency
import com.voxapps.calendarapp.data.WeekdayMask
import java.io.InputStream
import java.io.OutputStream
import java.util.Date
import java.util.UUID

data class ParsedIcsEntry(
    val uid: String,
    val type: CalendarEntryType,
    val title: String,
    val description: String?,
    val location: String?,
    val startMillis: Long,
    val endMillis: Long?,
    val allDay: Boolean,
    val completed: Boolean,
    val recurrenceFrequency: RecurrenceFrequency,
    val recurrenceInterval: Int,
    val recurrenceUntilMillis: Long?,
    val recurrenceDaysMask: Int = 0,
    val layerName: String?,
    val tags: List<String>
)

/** [suggestedName] is the source calendar's own name, pulled from the ICS `X-WR-CALNAME` property
 *  when present — used to pre-fill the "new calendar" name field in [IcsSettingsTab]'s import-target
 *  dialog, falling back to the picked file's own name when absent. */
data class ParsedIcsFile(val suggestedName: String?, val entries: List<ParsedIcsEntry>)

/**
 * Vox Calendar's own ICS import/export (its Settings screen, not Hub's JSON backup — the two are
 * fully independent). Uses `net.sf.biweekly:biweekly` rather than a hand-rolled RFC 5545 parser/writer
 * — see the implementation plan's rationale (RRULE/line-folding/escaping are the easiest parts of the
 * spec to get wrong by hand, and biweekly is a small, pure-Java, few-dependency library already proven
 * across the Android sync-client ecosystem).
 *
 * Each layer maps to the ICS `CATEGORIES` property (layer name first, then tags) rather than one file
 * per layer — a single portable file is the spec-correct way to fit "layers" into ICS, and matches how
 * Google/Apple/Thunderbird import a whole .ics as one flat event list anyway (their own "which
 * calendar" concept is account-level, not part of the ICS file itself).
 */
object IcsExportImportUtil {

    /** [entries] is expected to already exclude to-do-flavored rows ([CalendarEntry.listId] != null —
     *  see [com.voxapps.calendarapp.receiver.CalendarExportImportHandler], which filters those out
     *  before calling this) — a to-do checklist item isn't a calendar-standard ICS concept, and may
     *  have no date at all, so every entry reaching here is guaranteed a plain Event/Task with a
     *  non-null [CalendarEntry.startMillis]. */
    fun write(entries: List<CalendarEntryWithTags>, layers: List<CalendarLayer>, output: OutputStream) {
        val layerById = layers.associateBy { it.id }
        val ical = ICalendar()
        entries.forEach { ewt ->
            val entry = ewt.entry
            val startMillis = entry.startMillis!!
            val layerName = layerById[entry.layerId]?.name
            val categories = (listOfNotNull(layerName) + ewt.tagNames)
            if (entry.type == CalendarEntryType.TASK) {
                val todo = VTodo()
                todo.setUid(entry.uid)
                todo.setSummary(entry.title)
                entry.description?.let { todo.setDescription(it) }
                entry.location?.let { todo.setLocation(it) }
                todo.setDateDue(toIcalDate(startMillis, entry.allDay))
                if (entry.completed) todo.setStatus(Status.completed())
                if (categories.isNotEmpty()) todo.addCategories(*categories.toTypedArray())
                applyRecurrence(entry)?.let { todo.setRecurrenceRule(it) }
                ical.addTodo(todo)
            } else {
                val event = VEvent()
                event.setUid(entry.uid)
                event.setSummary(entry.title)
                entry.description?.let { event.setDescription(it) }
                entry.location?.let { event.setLocation(it) }
                event.setDateStart(toIcalDate(startMillis, entry.allDay))
                entry.endMillis?.let { event.setDateEnd(toIcalDate(it, entry.allDay)) }
                if (categories.isNotEmpty()) event.addCategories(*categories.toTypedArray())
                applyRecurrence(entry)?.let { event.setRecurrenceRule(it) }
                ical.addEvent(event)
            }
        }
        Biweekly.write(ical).go(output)
    }

    fun read(input: InputStream): List<ParsedIcsEntry> = parseEntries(Biweekly.parse(input).all())

    /** Same parse as [read], plus the source calendar's own name (see [ParsedIcsFile]) — used by the
     *  import-target dialog to pre-fill a "new calendar" name. */
    fun readWithSuggestedName(input: InputStream): ParsedIcsFile {
        val icals = Biweekly.parse(input).all()
        val suggestedName = icals.firstNotNullOfOrNull { it.getExperimentalProperty("X-WR-CALNAME")?.value }
        return ParsedIcsFile(suggestedName, parseEntries(icals))
    }

    private fun parseEntries(icals: List<ICalendar>): List<ParsedIcsEntry> {
        val result = mutableListOf<ParsedIcsEntry>()
        icals.forEach { ical ->
            ical.events.forEach { event ->
                val title = event.summary?.value?.takeIf { it.isNotBlank() } ?: return@forEach
                val start = event.dateStart?.value ?: return@forEach
                val categories = event.categories.flatMap { it.values }
                result.add(
                    ParsedIcsEntry(
                        uid = event.uid?.value ?: UUID.randomUUID().toString(),
                        type = CalendarEntryType.EVENT,
                        title = title,
                        description = event.description?.value,
                        location = event.location?.value,
                        startMillis = start.time,
                        endMillis = event.dateEnd?.value?.time,
                        allDay = !start.hasTime(),
                        completed = false,
                        recurrenceFrequency = frequencyOf(event.recurrenceRule?.value),
                        recurrenceInterval = event.recurrenceRule?.value?.interval ?: 1,
                        recurrenceUntilMillis = event.recurrenceRule?.value?.until?.time,
                        recurrenceDaysMask = daysMaskOf(event.recurrenceRule?.value),
                        layerName = categories.firstOrNull(),
                        tags = categories.drop(1)
                    )
                )
            }
            ical.todos.forEach { todo ->
                val title = todo.summary?.value?.takeIf { it.isNotBlank() } ?: return@forEach
                val due = todo.dateDue?.value ?: return@forEach
                val categories = todo.categories.flatMap { it.values }
                result.add(
                    ParsedIcsEntry(
                        uid = todo.uid?.value ?: UUID.randomUUID().toString(),
                        type = CalendarEntryType.TASK,
                        title = title,
                        description = todo.description?.value,
                        location = todo.location?.value,
                        startMillis = due.time,
                        endMillis = null,
                        allDay = !due.hasTime(),
                        completed = todo.status?.isCompleted == true,
                        recurrenceFrequency = frequencyOf(todo.recurrenceRule?.value),
                        recurrenceInterval = todo.recurrenceRule?.value?.interval ?: 1,
                        recurrenceUntilMillis = todo.recurrenceRule?.value?.until?.time,
                        recurrenceDaysMask = daysMaskOf(todo.recurrenceRule?.value),
                        layerName = categories.firstOrNull(),
                        tags = categories.drop(1)
                    )
                )
            }
        }
        return result
    }

    /**
     * Imports every [parsed] entry into exactly [targetLayerId], preserving the ICS `UID` so
     * re-importing the same file after a re-export stays stable rather than duplicating — ignores
     * each entry's own [ParsedIcsEntry.layerName]/`CATEGORIES` on purpose: the user explicitly picked
     * one target calendar for this whole file (see [IcsSettingsTab]'s import-target dialog), and an
     * ICS file represents one source calendar in practice, so per-entry category-based layer
     * auto-matching was only ever an accidental side effect of the old design, not a deliberately used
     * capability.
     */
    suspend fun importEntriesIntoLayer(repository: CalendarRepository, parsed: List<ParsedIcsEntry>, targetLayerId: Long) {
        parsed.forEach { entry ->
            repository.addEntry(
                uid = entry.uid,
                type = entry.type,
                title = entry.title,
                description = entry.description,
                location = entry.location,
                startMillis = entry.startMillis,
                endMillis = entry.endMillis,
                allDay = entry.allDay,
                completed = entry.completed,
                recurrenceFrequency = entry.recurrenceFrequency,
                recurrenceInterval = entry.recurrenceInterval,
                recurrenceUntilMillis = entry.recurrenceUntilMillis,
                recurrenceDaysMask = entry.recurrenceDaysMask,
                layerId = targetLayerId,
                tags = entry.tags
            )
        }
    }

    private fun toIcalDate(millis: Long, allDay: Boolean): ICalDate = ICalDate(Date(millis), !allDay)

    /** FREQ, INTERVAL, UNTIL, and (for WEEKLY) BYDAY are round-tripped — anything else (COUNT,
     *  BYMONTHDAY, ...) is dropped rather than attempting a full RRULE engine, per the
     *  deliberately-minimal recurrence model. */
    private fun applyRecurrence(entry: CalendarEntry): Recurrence? {
        val frequency = when (entry.recurrenceFrequency) {
            RecurrenceFrequency.NONE -> return null
            RecurrenceFrequency.DAILY -> Frequency.DAILY
            RecurrenceFrequency.WEEKLY -> Frequency.WEEKLY
            RecurrenceFrequency.MONTHLY -> Frequency.MONTHLY
            RecurrenceFrequency.YEARLY -> Frequency.YEARLY
        }
        val builder = Recurrence.Builder(frequency)
        if (entry.recurrenceInterval > 1) builder.interval(entry.recurrenceInterval)
        entry.recurrenceUntilMillis?.let { builder.until(Date(it)) }
        if (entry.recurrenceFrequency == RecurrenceFrequency.WEEKLY) {
            val mask = entry.recurrenceDaysMask and WeekdayMask.ALL
            java.time.DayOfWeek.entries.forEach { day ->
                if (WeekdayMask.contains(mask, day)) builder.byDay(icalDayOf(day))
            }
        }
        return builder.build()
    }

    /** BYDAY (WEEKLY only) back into a [WeekdayMask]-encoded set; prefixed BYDAY parts ("2MO", the
     *  nth-weekday-of-month form) don't fit the weekly model and are skipped. */
    private fun daysMaskOf(recurrence: Recurrence?): Int {
        if (recurrence == null || recurrence.frequency != Frequency.WEEKLY) return 0
        var mask = 0
        recurrence.byDay.forEach { byDay ->
            if (byDay.num != null && byDay.num != 0) return@forEach
            val day = when (byDay.day) {
                DayOfWeek.MONDAY -> java.time.DayOfWeek.MONDAY
                DayOfWeek.TUESDAY -> java.time.DayOfWeek.TUESDAY
                DayOfWeek.WEDNESDAY -> java.time.DayOfWeek.WEDNESDAY
                DayOfWeek.THURSDAY -> java.time.DayOfWeek.THURSDAY
                DayOfWeek.FRIDAY -> java.time.DayOfWeek.FRIDAY
                DayOfWeek.SATURDAY -> java.time.DayOfWeek.SATURDAY
                DayOfWeek.SUNDAY -> java.time.DayOfWeek.SUNDAY
                else -> null
            } ?: return@forEach
            mask = mask or WeekdayMask.bit(day)
        }
        return mask
    }

    private fun icalDayOf(day: java.time.DayOfWeek): DayOfWeek = when (day) {
        java.time.DayOfWeek.MONDAY -> DayOfWeek.MONDAY
        java.time.DayOfWeek.TUESDAY -> DayOfWeek.TUESDAY
        java.time.DayOfWeek.WEDNESDAY -> DayOfWeek.WEDNESDAY
        java.time.DayOfWeek.THURSDAY -> DayOfWeek.THURSDAY
        java.time.DayOfWeek.FRIDAY -> DayOfWeek.FRIDAY
        java.time.DayOfWeek.SATURDAY -> DayOfWeek.SATURDAY
        java.time.DayOfWeek.SUNDAY -> DayOfWeek.SUNDAY
    }

    private fun frequencyOf(recurrence: Recurrence?): RecurrenceFrequency = when (recurrence?.frequency) {
        Frequency.DAILY -> RecurrenceFrequency.DAILY
        Frequency.WEEKLY -> RecurrenceFrequency.WEEKLY
        Frequency.MONTHLY -> RecurrenceFrequency.MONTHLY
        Frequency.YEARLY -> RecurrenceFrequency.YEARLY
        else -> RecurrenceFrequency.NONE
    }
}
