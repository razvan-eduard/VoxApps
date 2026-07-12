package com.voxapps.calendarapp.domain.ics

import biweekly.Biweekly
import biweekly.ICalendar
import biweekly.component.VEvent
import biweekly.component.VTodo
import biweekly.property.Status
import biweekly.util.Frequency
import biweekly.util.ICalDate
import biweekly.util.Recurrence
import com.voxapps.calendarapp.data.CalendarEntry
import com.voxapps.calendarapp.data.CalendarEntryType
import com.voxapps.calendarapp.data.CalendarEntryWithTags
import com.voxapps.calendarapp.data.CalendarLayer
import com.voxapps.calendarapp.data.CalendarLayerPalette
import com.voxapps.calendarapp.data.CalendarRepository
import com.voxapps.calendarapp.data.RecurrenceFrequency
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
    val recurrenceUntilMillis: Long?,
    val layerName: String?,
    val tags: List<String>
)

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

    fun write(entries: List<CalendarEntryWithTags>, layers: List<CalendarLayer>, output: OutputStream) {
        val layerById = layers.associateBy { it.id }
        val ical = ICalendar()
        entries.forEach { ewt ->
            val entry = ewt.entry
            val layerName = layerById[entry.layerId]?.name
            val categories = (listOfNotNull(layerName) + ewt.tagNames)
            if (entry.type == CalendarEntryType.TASK) {
                val todo = VTodo()
                todo.setUid(entry.uid)
                todo.setSummary(entry.title)
                entry.description?.let { todo.setDescription(it) }
                entry.location?.let { todo.setLocation(it) }
                todo.setDateDue(toIcalDate(entry.startMillis, entry.allDay))
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
                event.setDateStart(toIcalDate(entry.startMillis, entry.allDay))
                entry.endMillis?.let { event.setDateEnd(toIcalDate(it, entry.allDay)) }
                if (categories.isNotEmpty()) event.addCategories(*categories.toTypedArray())
                applyRecurrence(entry)?.let { event.setRecurrenceRule(it) }
                ical.addEvent(event)
            }
        }
        Biweekly.write(ical).go(output)
    }

    fun read(input: InputStream): List<ParsedIcsEntry> {
        val icals = Biweekly.parse(input).all()
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
                        recurrenceUntilMillis = event.recurrenceRule?.value?.until?.time,
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
                        recurrenceUntilMillis = todo.recurrenceRule?.value?.until?.time,
                        layerName = categories.firstOrNull(),
                        tags = categories.drop(1)
                    )
                )
            }
        }
        return result
    }

    /**
     * Resolves each parsed entry's layer by name (case-insensitive, create-if-missing — mirrors
     * `ExpensesExportImportHandler`'s exact `nameToId[name.lowercase()]` merge-by-name pattern) and
     * inserts it, preserving the ICS `UID` so re-importing the same file after a re-export stays
     * stable rather than duplicating.
     */
    suspend fun importEntries(repository: CalendarRepository, parsed: List<ParsedIcsEntry>) {
        val layers = repository.layersSnapshot().toMutableList()
        val nameToId = layers.associate { it.name.lowercase() to it.id }.toMutableMap()
        val defaultLayerId = layers.firstOrNull { it.isDefault }?.id ?: layers.firstOrNull()?.id

        parsed.forEach { entry ->
            val layerId = if (entry.layerName == null) {
                defaultLayerId
            } else {
                nameToId[entry.layerName.lowercase()] ?: run {
                    val newId = repository.addLayer(
                        name = entry.layerName,
                        colorArgb = CalendarLayerPalette.unusedOrRandomColor(layers.map { it.colorArgb }),
                        position = layers.size
                    )
                    nameToId[entry.layerName.lowercase()] = newId
                    layers.add(CalendarLayer(id = newId, name = entry.layerName, colorArgb = 0L, position = layers.size, createdAt = 0L))
                    newId
                }
            } ?: return@forEach

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
                recurrenceUntilMillis = entry.recurrenceUntilMillis,
                layerId = layerId,
                tags = entry.tags
            )
        }
    }

    private fun toIcalDate(millis: Long, allDay: Boolean): ICalDate = ICalDate(Date(millis), !allDay)

    /** Only FREQ and UNTIL are round-tripped — anything else (BYDAY, INTERVAL, COUNT, ...) is dropped
     *  rather than attempting a full RRULE engine, per the deliberately-minimal recurrence model. */
    private fun applyRecurrence(entry: CalendarEntry): Recurrence? {
        val frequency = when (entry.recurrenceFrequency) {
            RecurrenceFrequency.NONE -> return null
            RecurrenceFrequency.DAILY -> Frequency.DAILY
            RecurrenceFrequency.WEEKLY -> Frequency.WEEKLY
            RecurrenceFrequency.MONTHLY -> Frequency.MONTHLY
            RecurrenceFrequency.YEARLY -> Frequency.YEARLY
        }
        val builder = Recurrence.Builder(frequency)
        entry.recurrenceUntilMillis?.let { builder.until(Date(it)) }
        return builder.build()
    }

    private fun frequencyOf(recurrence: Recurrence?): RecurrenceFrequency = when (recurrence?.frequency) {
        Frequency.DAILY -> RecurrenceFrequency.DAILY
        Frequency.WEEKLY -> RecurrenceFrequency.WEEKLY
        Frequency.MONTHLY -> RecurrenceFrequency.MONTHLY
        Frequency.YEARLY -> RecurrenceFrequency.YEARLY
        else -> RecurrenceFrequency.NONE
    }
}
