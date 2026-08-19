package com.voxapps.calendarapp.domain.llm

import android.content.Context
import android.widget.Toast
import com.voxapps.calendarapp.data.CalendarEntryType
import com.voxapps.calendarapp.data.preferences.CalendarSettings
import com.voxapps.calendarapp.di.CalendarContainer
import com.voxapps.logging.Logger
import com.voxapps.recordflow.AskScope
import com.voxapps.recordflow.DeterministicReading
import com.voxapps.recordflow.FieldWeight
import com.voxapps.recordflow.FlowSupport
import com.voxapps.recordflow.RecordFlowSpec
import com.voxapps.recordflow.RecordSource
import com.voxapps.textmatch.extract.DateTimeExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private const val TAG = "CalendarScanFlow"

/** What a page yields about when something happens, before anything is asked of anyone. */
data class ScannedMoment(val text: String, val date: LocalDate?, val time: LocalTime?)

/**
 * A scanned page becoming a calendar entry.
 *
 * An entry is incomplete without a moment in time, and that is the whole difference between this
 * flow and a note's: text alone is not a record here. What the device can establish without
 * interpreting anything is a date written in digits — the same refusal [DateTimeExtractor] makes by
 * declining to read "9 PM", which needs locale knowledge it does not have.
 *
 * So the offline rung is real but narrow, and where it finds nothing there is nothing to file. An
 * entry placed on the day it happened to be scanned would be worse than a scan that visibly did
 * nothing, because only one of the two is noticeable afterwards.
 */
class CalendarScanFlow(
    private val context: Context,
    private val container: CalendarContainer
) : RecordFlowSpec<String, ScannedMoment, CalendarEventParseResultParser.Parsed> {

    override val source = RecordSource.SCAN
    override val support: FlowSupport = CalendarSettings.SCAN_FLOW_SUPPORT
    override val taskId = LlmTasks.CALENDAR_SCAN_CLEANUP

    override suspend fun read(input: String): DeterministicReading<ScannedMoment> {
        val text = input.trim()
        val date = DateTimeExtractor.findDates(text).minByOrNull { it.value }?.value
        val time = DateTimeExtractor.findTimes(text).minByOrNull { it.value }?.value
        return DeterministicReading(
            fields = ScannedMoment(text, date, time),
            usable = text.isNotBlank(),
            // An entry needs a day. A time is a refinement — without one it is an all-day entry,
            // which is a real answer rather than a missing one.
            complete = text.isNotBlank() && date != null
        )
    }

    override suspend fun prompt(reading: DeterministicReading<ScannedMoment>, asks: AskScope): String {
        val layers = container.calendarRepository.layers.first().map { it.name }
        val lists = container.toDoRepository.lists.first().map { it.title }
        val language = container.settingsRepository.getSnapshot().language
        return CalendarScanCleanupPromptBuilder.build(reading.fields.text, layers, lists, language)
    }

    override suspend fun parse(reply: String): CalendarEventParseResultParser.Parsed? =
        CalendarEventParseResultParser.parse(reply)

    override suspend fun commit(
        reading: DeterministicReading<ScannedMoment>?,
        parsed: CalendarEventParseResultParser.Parsed?,
        applies: (FieldWeight) -> Boolean
    ): Long? {
        if (parsed != null) {
            Logger.d(TAG, "Creating ${parsed.kind} '${parsed.title}' from a model's answer")
            com.voxapps.calendarapp.receiver.LlmResultReceiver().routeParsed(container, parsed)
            return null
        }

        val moment = reading?.fields ?: return null
        val date = moment.date ?: return null
        val start = date.atTime(moment.time ?: LocalTime.MIDNIGHT)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val settings = container.settingsRepository.getSnapshot()

        // The same write point a model's answer goes through, so the layer is resolved the one way.
        // No spoken layer, because nothing said one.
        container.calendarRepository.addParsedEntry(
            type = CalendarEntryType.EVENT,
            title = moment.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(TITLE_LIMIT)
                ?: moment.text.take(TITLE_LIMIT),
            description = moment.text,
            location = null,
            startMillis = start,
            endMillis = start,
            allDay = moment.time == null,
            tags = emptyList(),
            spokenLayer = null,
            defaultLayerId = settings.defaultLayerId,
            autoCreateLayer = false
        )
        Logger.d(TAG, "Created an entry without a model on $date (allDay=${moment.time == null})")
        return null
    }

    /**
     * This app keeps no list of records waiting to be finished, so the person is told instead — which
     * is the same thing a queue does, minus the waiting: the page is still in their hand, and an
     * entry they make themselves is one nobody had to guess at.
     */
    override suspend fun queueForReview(
        reading: DeterministicReading<ScannedMoment>?,
        parsed: CalendarEventParseResultParser.Parsed?
    ) {
        val key = if (reading?.fields?.date == null) "scan_no_date_offline" else "scan_save_failed"
        Logger.d(TAG, "Nothing filed from this scan — telling the user ($key)")
        withContext(Dispatchers.Main) {
            Toast.makeText(
                context.applicationContext,
                container.languageManager.getString(key),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    companion object {
        private const val TITLE_LIMIT = 80
    }
}
