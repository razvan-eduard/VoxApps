package com.voxapps.notes.domain.llm

import com.voxapps.ipc.VoxCommand
import com.voxapps.notes.data.preferences.NotesSettings
import com.voxapps.notes.di.NotesContainer
import com.voxapps.recordflow.AskScope
import com.voxapps.recordflow.DeterministicReading
import com.voxapps.recordflow.FieldWeight
import com.voxapps.recordflow.FlowSupport
import com.voxapps.recordflow.LlmLevel
import com.voxapps.recordflow.RecordFlowSpec
import com.voxapps.recordflow.RecordSource

/** A spoken note as it arrives: the words, and whatever the classifier said they belong to. */
data class SpokenNote(val title: String?, val text: String, val spokenCategory: String?)

/**
 * A spoken utterance becoming a note.
 *
 * The shortest flow there is, and the reason is the same one that runs through this app: a note's
 * text *is* the record. Nothing has to be extracted from the words to make them a note, so the
 * offline rung is not a reduced version of anything — it is the whole behaviour, and it is what this
 * app has always done. That is why [NotesSettings.VOICE_FLOW_SUPPORT] offers one rung: there is no
 * second thing a model could be let in to do.
 *
 * Written as a flow anyway, because the shape is what is shared rather than the work: this is where
 * a spoken note is read and written, and a reader looking for that should find it in the same place
 * as its equivalents in the other apps.
 */
class NoteVoiceFlow(
    private val container: NotesContainer,
    /**
     * Handed what the save resolved — which category the spoken one turned out to be. Per capture,
     * like every other fact neither the reading nor a reply carries, and the caller's business
     * rather than the flow's: whether a note announces itself is a setting this has no opinion on.
     */
    private val onSaved: (com.voxapps.notes.data.VoiceNoteResult) -> Unit = {}
) : RecordFlowSpec<VoxCommand, SpokenNote, Unit> {

    override val source = RecordSource.VOICE
    override val support: FlowSupport = NotesSettings.VOICE_FLOW_SUPPORT
    /** Nothing is ever asked, so no reply is ever routed back under a task of this flow's. */
    override val taskId = ""

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

    override suspend fun prompt(reading: DeterministicReading<SpokenNote>, asks: AskScope): String? = null

    override suspend fun parse(reply: String): Unit? = null

    override suspend fun commit(
        reading: DeterministicReading<SpokenNote>?,
        parsed: Unit?,
        applies: (FieldWeight) -> Boolean
    ): Long? {
        val spoken = reading?.fields ?: return null
        val settings = container.settingsRepository.getSnapshot()
        val saved = container.notesRepository.addVoiceNote(
            title = spoken.title,
            text = spoken.text,
            spokenCategory = spoken.spokenCategory,
            defaultCategoryId = settings.defaultVoiceCategoryId,
            autoCreate = settings.autoCreateVoiceCategory,
            createdAt = System.currentTimeMillis()
        )
        onSaved(saved)
        return saved.noteId.takeIf { it > 0 }
    }

    /** Unreachable: a reading here is complete or it is not usable, and neither leads here. Kept
     *  honest rather than throwing — a note with no words is nothing to review. */
    override suspend fun queueForReview(
        reading: DeterministicReading<SpokenNote>?,
        parsed: Unit?
    ) = Unit
}
