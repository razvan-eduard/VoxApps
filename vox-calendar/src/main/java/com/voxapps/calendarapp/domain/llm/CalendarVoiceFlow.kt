package com.voxapps.calendarapp.domain.llm

import android.content.Context
import android.widget.Toast
import com.voxapps.calendarapp.data.preferences.CalendarSettings
import com.voxapps.calendarapp.di.CalendarContainer
import com.voxapps.ipc.VoxSatelliteSchema
import com.voxapps.recordflow.AskScope
import com.voxapps.recordflow.DeterministicReading
import com.voxapps.recordflow.FieldWeight
import com.voxapps.recordflow.FlowSupport
import com.voxapps.recordflow.RecordFlowSpec
import com.voxapps.recordflow.RecordSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * A spoken utterance becoming a calendar entry.
 *
 * Nothing is established on the device before the question is asked, and the reason is specific
 * rather than incidental: an entry needs a moment, and spoken time is language — "next Tuesday",
 * "in a fortnight". The deterministic extractor this app uses for scanned pages settles digits and
 * declines everything else, which is right for a page and useless for a sentence.
 *
 * So the full rung's question is the whole of it, written as a template because Commander is what
 * hears the words, and the committed entry keeps the sentence as its description. The offline rung
 * asks nothing and settles nothing: the sentence lands as a dateless to-do in the review list —
 * [queueForReview] — because an entry filed on a guessed day is worse than one visibly waiting to
 * be filed. A reply that cannot be used lands in the same place.
 */
class CalendarVoiceFlow(
    private val context: Context,
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
        // A reply landing at a rung that accepts nothing back is filed, not written — the level
        // moved to the offline rung while this question was in flight.
        if (!applies(FieldWeight.HEAD)) {
            queueForReview(reading, parsed)
            return null
        }
        com.voxapps.calendarapp.receiver.LlmResultReceiver().routeParsed(
            container, answer, transcript = reading?.fields?.takeIf { it.isNotBlank() }
        )
        return null
    }

    /**
     * The words, kept where a person will file them: a dateless to-do in a dedicated review list,
     * created on first use. The list is found by its localized title, so changing the app language
     * starts a fresh list under the new name — the old one keeps its items. Reached from the
     * offline rung at dispatch and from a reply that could not be read.
     */
    override suspend fun queueForReview(
        reading: DeterministicReading<String>?,
        parsed: CalendarEventParseResultParser.Parsed?
    ) {
        val transcript = reading?.fields?.takeIf { it.isNotBlank() } ?: return
        val title = container.languageManager.getString("voice_review_list_title")
        val listId = container.toDoRepository.lists.first()
            .firstOrNull { it.title.equals(title, ignoreCase = true) }?.id
            ?: run {
                val settings = container.settingsRepository.getSnapshot()
                val layers = container.calendarRepository.layersSnapshot()
                val layerId = settings.defaultLayerId
                    ?: layers.firstOrNull { it.isDefault }?.id
                    ?: layers.firstOrNull()?.id
                    ?: return // no layer at all: nothing this could be filed under
                container.toDoRepository.createList(title, layerId)
            }
        container.toDoRepository.addItem(listId, transcript)
        withContext(Dispatchers.Main) {
            Toast.makeText(
                context,
                container.languageManager.getString("voice_queued_for_review"),
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
