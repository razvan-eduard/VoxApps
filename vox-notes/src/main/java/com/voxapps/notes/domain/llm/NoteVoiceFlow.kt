package com.voxapps.notes.domain.llm

import com.voxapps.datahygiene.FieldCleaner
import com.voxapps.ipc.VoxCommand
import com.voxapps.ipc.VoxSatelliteSchema
import com.voxapps.notes.data.preferences.NotesSettings
import com.voxapps.notes.di.NotesContainer
import com.voxapps.recordflow.AskScope
import com.voxapps.recordflow.DeterministicReading
import com.voxapps.recordflow.FieldWeight
import com.voxapps.recordflow.FlowSupport
import com.voxapps.recordflow.RecordFlowSpec
import com.voxapps.recordflow.RecordSource
import kotlinx.coroutines.flow.first

/** A spoken note as it arrives: the words, and whatever the classifier said they belong to. */
data class SpokenNote(val title: String?, val text: String, val spokenCategory: String?)

/**
 * A spoken utterance becoming a note.
 *
 * The words are the note, so the offline rung is the whole behaviour rather than a reduced one:
 * [read] establishes the transcript as complete and [commit] writes it untouched. The full rung
 * lets a model do for a spoken note what it already does for a scanned one — tidy the body and
 * suggest a title and a category, through the same prompt and parser the scan uses. A reply that
 * cannot be used falls back to the untouched transcript ([queueForReview]), so nothing spoken is
 * lost either way.
 */
class NoteVoiceFlow(
    private val container: NotesContainer,
    /**
     * Handed what the save resolved — which category the spoken one turned out to be. Per capture,
     * like every other fact neither the reading nor a reply carries, and the caller's business
     * rather than the flow's: whether a note announces itself is a setting this has no opinion on.
     */
    private val onSaved: (com.voxapps.notes.data.VoiceNoteResult) -> Unit = {}
) : RecordFlowSpec<VoxCommand, SpokenNote, NoteScanCleanupResultParser.Cleaned> {

    override val source = RecordSource.VOICE
    override val support: FlowSupport = NotesSettings.VOICE_FLOW_SUPPORT
    override val taskId = LlmTasks.NOTE_PARSE

    override suspend fun read(input: VoxCommand): DeterministicReading<SpokenNote> {
        val text = input.text.orEmpty().trim()
        return DeterministicReading(
            fields = SpokenNote(input.title, text, input.category),
            usable = text.isNotBlank() || !input.title.isNullOrBlank(),
            // Whole on arrival. A title and a category are refinements, and the classification that
            // produced the category happened before this flow was reached.
            complete = text.isNotBlank() || !input.title.isNullOrBlank()
        )
    }

    override suspend fun prompt(reading: DeterministicReading<SpokenNote>, asks: AskScope): String =
        buildTemplate().replace(VoxSatelliteSchema.INPUT_PLACEHOLDER, reading.fields.text)

    /** The same question with the words left out, for the transport that supplies them. */
    override suspend fun promptTemplate(asks: AskScope): String = buildTemplate()

    private suspend fun buildTemplate(): String {
        val categories = container.notesRepository.categories.first().map { it.name }
        val language = container.settingsRepository.getSnapshot().language
        return NoteScanCleanupPromptBuilder.buildTemplate(categories, language)
    }

    override suspend fun parse(reply: String): NoteScanCleanupResultParser.Cleaned? =
        NoteScanCleanupResultParser.parse(reply)

    override suspend fun commit(
        reading: DeterministicReading<SpokenNote>?,
        parsed: NoteScanCleanupResultParser.Cleaned?,
        applies: (FieldWeight) -> Boolean
    ): Long? {
        val spoken = reading?.fields
        val settings = container.settingsRepository.getSnapshot()
        // A reply landing at a rung that accepts nothing back is not used — the transcript is the
        // note, exactly as the offline rung would have written it.
        val useParsed = parsed != null && applies(FieldWeight.HEAD)
        val saved = when {
            useParsed -> container.notesRepository.addVoiceNote(
                // Belt-and-suspenders past the JSON-parse layer's own optCleanString guard.
                title = FieldCleaner.clean(parsed!!.title, "title", "Note") ?: spoken?.title,
                text = parsed.text,
                spokenCategory = FieldCleaner.clean(parsed.category, "category", "Note")
                    ?: spoken?.spokenCategory,
                defaultCategoryId = settings.defaultVoiceCategoryId,
                autoCreate = settings.autoCreateVoiceCategory,
                createdAt = System.currentTimeMillis()
            )
            spoken != null -> container.notesRepository.addVoiceNote(
                title = spoken.title,
                text = spoken.text,
                spokenCategory = spoken.spokenCategory,
                defaultCategoryId = settings.defaultVoiceCategoryId,
                autoCreate = settings.autoCreateVoiceCategory,
                createdAt = System.currentTimeMillis()
            )
            else -> return null
        }
        onSaved(saved)
        return saved.noteId.takeIf { it > 0 }
    }

    /** The full rung's fallback: an unreadable reply commits the untouched transcript through the
     *  offline path, so a spoken note never depends on the model having understood it. */
    override suspend fun queueForReview(
        reading: DeterministicReading<SpokenNote>?,
        parsed: NoteScanCleanupResultParser.Cleaned?
    ) {
        reading?.let { commit(it, null) { true } }
    }
}
