package com.voxapps.calendarapp.domain.llm

import com.voxapps.calendarapp.data.preferences.CalendarSettings
import com.voxapps.calendarapp.di.CalendarContainer
import com.voxapps.ipc.VoxSatelliteSchema
import com.voxapps.recordflow.AskScope
import com.voxapps.recordflow.DeterministicReading
import com.voxapps.recordflow.FieldWeight
import com.voxapps.recordflow.FlowSupport
import com.voxapps.recordflow.RecordFlowSpec
import com.voxapps.recordflow.RecordSource
import kotlinx.coroutines.flow.first

/**
 * A spoken utterance becoming a calendar entry.
 *
 * Nothing is established on the device before the question is asked, and the reason is specific
 * rather than incidental: an entry needs a moment, and spoken time is language — "next Tuesday",
 * "in a fortnight". The deterministic extractor this app uses for scanned pages settles digits and
 * declines everything else, which is right for a page and useless for a sentence.
 *
 * So the question is the whole of it, written as a template because Commander is what hears the
 * words. [read] does receive the sentence on both routes and settles nothing from it — the sentence
 * survives the round trip so that a rule *could* be applied here, not because one may be.
 */
class CalendarVoiceFlow(
    private val container: CalendarContainer
) : RecordFlowSpec<String, String, CalendarEventParseResultParser.Parsed> {

    override val source = RecordSource.VOICE
    override val support: FlowSupport = CalendarSettings.VOICE_FLOW_SUPPORT
    override val taskId = LlmTasks.CALENDAR_EVENT_PARSE

    override suspend fun read(input: String): DeterministicReading<String> =
        DeterministicReading(fields = input, usable = input.isNotBlank(), complete = false)

    override suspend fun prompt(reading: DeterministicReading<String>, asks: AskScope): String =
        buildTemplate().replace(VoxSatelliteSchema.INPUT_PLACEHOLDER, reading.fields)

    /** The same question with the words left out, for the transport that supplies them. */
    override suspend fun promptTemplate(asks: AskScope): String = buildTemplate()

    private suspend fun buildTemplate(): String {
        val settings = container.settingsRepository.getSnapshot()
        val layers = container.calendarRepository.layers.first().map { it.name }
        val lists = container.toDoRepository.lists.first().map { it.title }
        return CalendarEventParsePromptBuilder.buildTemplate(layers, lists, settings.language)
    }

    override suspend fun parse(reply: String): CalendarEventParseResultParser.Parsed? =
        CalendarEventParseResultParser.parse(reply)

    override suspend fun commit(
        reading: DeterministicReading<String>?,
        parsed: CalendarEventParseResultParser.Parsed?,
        applies: (FieldWeight) -> Boolean
    ): Long? {
        val answer = parsed ?: return null
        com.voxapps.calendarapp.receiver.LlmResultReceiver().routeParsed(container, answer)
        return null
    }

    /** A spoken entry the model could not read leaves no record: the speaker still knows what they
     *  said, and this app keeps no list of half-heard things. */
    override suspend fun queueForReview(
        reading: DeterministicReading<String>?,
        parsed: CalendarEventParseResultParser.Parsed?
    ) = Unit
}
