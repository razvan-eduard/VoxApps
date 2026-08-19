package com.voxapps.notes.domain.llm

import android.content.Context
import android.widget.Toast
import androidx.glance.appwidget.updateAll
import com.voxapps.attachments.AttachmentEntity
import com.voxapps.attachments.AttachmentFileStore
import com.voxapps.attachments.AttachmentSource
import com.voxapps.datahygiene.FieldCleaner
import com.voxapps.logging.Logger
import com.voxapps.notes.data.NotesAttachments
import com.voxapps.notes.data.preferences.NotesSettings
import com.voxapps.notes.di.NotesContainer
import com.voxapps.notes.ui.widget.NotesWidget
import com.voxapps.recordflow.AskScope
import com.voxapps.recordflow.DeterministicReading
import com.voxapps.recordflow.FieldWeight
import com.voxapps.recordflow.FlowSupport
import com.voxapps.recordflow.RecordFlowSpec
import com.voxapps.recordflow.RecordSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "NoteScanFlow"

/**
 * A scanned page becoming a note, at whatever level the model is let in.
 *
 * The one thing worth stating plainly is what "complete" means here, because it is what makes the
 * offline rung honest rather than a degraded version of the others: a note needs its text and
 * nothing else. A title and a category are refinements — pleasant to have, never missing — so a page
 * that produced readable text has produced a whole note already, and the model is only ever asked to
 * improve one, not to finish one. That is the same reason this app's voice flow declares it needs no
 * extraction pass at all.
 *
 * [stagedImageName] is per capture rather than per flow, which is why this is built fresh each time:
 * it is the one fact both halves need and neither the reading nor the reply carries — the reading is
 * gone by the time an answer arrives, and the answer never knew about the photograph.
 */
class NoteScanFlow(
    private val context: Context,
    private val container: NotesContainer,
    private val stagedImageName: String?
) : RecordFlowSpec<String, String, NoteScanCleanupResultParser.Cleaned> {

    override val source = RecordSource.SCAN
    override val support: FlowSupport = NotesSettings.SCAN_FLOW_SUPPORT
    override val taskId = LlmTasks.NOTE_SCAN_CLEANUP

    override suspend fun read(input: String): DeterministicReading<String> {
        val text = input.trim()
        return DeterministicReading(
            fields = text,
            usable = text.isNotBlank(),
            // A note is whole once it has text; see this class's own comment for why that is not a
            // shortcut.
            complete = text.isNotBlank()
        )
    }

    override suspend fun prompt(reading: DeterministicReading<String>, asks: AskScope): String {
        val categories = container.notesRepository.categories.first().map { it.name }
        val language = container.settingsRepository.getSnapshot().language
        return NoteScanCleanupPromptBuilder.build(reading.fields, categories, language)
    }

    override suspend fun parse(reply: String): NoteScanCleanupResultParser.Cleaned? =
        NoteScanCleanupResultParser.parse(reply)

    override suspend fun commit(
        reading: DeterministicReading<String>?,
        parsed: NoteScanCleanupResultParser.Cleaned?,
        applies: (FieldWeight) -> Boolean
    ): Long? {
        val settings = container.settingsRepository.getSnapshot()
        val noteId = if (parsed != null) {
            // Belt-and-suspenders past the JSON-parse layer's own optCleanString guard.
            container.notesRepository.addVoiceNote(
                title = FieldCleaner.clean(parsed.title, "title", "Note"),
                text = parsed.text,
                spokenCategory = FieldCleaner.clean(parsed.category, "category", "Note"),
                defaultCategoryId = settings.defaultVoiceCategoryId,
                autoCreate = settings.autoCreateVoiceCategory,
                createdAt = System.currentTimeMillis()
            ).noteId
        } else {
            val text = reading?.fields ?: return null
            // Nothing was asked, so nothing is chosen: the default category takes it, as it does for
            // a voice note, and the title is left for whoever reads it.
            container.notesRepository.addNote(
                title = null,
                text = text,
                categoryId = settings.defaultVoiceCategoryId,
                createdAt = System.currentTimeMillis()
            ).also { Logger.d(TAG, "Created a note without a model: ${text.length} char(s)") }
        }

        keepOrDiscardPhoto(settings, noteId, keepWhen = NotesSettings.RETENTION_ALWAYS)
        if (noteId > 0) refreshWidget()
        return noteId.takeIf { it > 0 }
    }

    /**
     * Notes has no review list, and does not need one: what it has instead is a note that exists,
     * carries the photograph, and says on its face that a person has to finish it. That is the same
     * thing a queue is for — the record waits where it will be seen — and it is where an unreadable
     * reply has always landed.
     */
    override suspend fun queueForReview(
        reading: DeterministicReading<String>?,
        parsed: NoteScanCleanupResultParser.Cleaned?
    ) {
        val settings = container.settingsRepository.getSnapshot()
        if (stagedImageName == null || settings.scanImageRetention == NotesSettings.RETENTION_NEVER) {
            if (stagedImageName != null) {
                AttachmentFileStore.delete(context.applicationContext, NotesAttachments.DIR, stagedImageName)
            }
            // Unconditional: the only signal that the scan produced no note.
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    container.languageManager.getString("scan_save_failed"),
                    Toast.LENGTH_SHORT
                ).show()
            }
            return
        }

        val noteId = container.notesRepository.addStubNote(
            container.languageManager.getString("manual_review_required"),
            System.currentTimeMillis()
        )
        if (noteId > 0) {
            attach(noteId, stagedImageName)
            refreshWidget()
        }
    }

    private suspend fun keepOrDiscardPhoto(settings: NotesSettings, noteId: Long, keepWhen: String) {
        val name = stagedImageName ?: return
        if (settings.scanImageRetention == keepWhen && noteId > 0) {
            attach(noteId, name)
        } else {
            AttachmentFileStore.delete(context.applicationContext, NotesAttachments.DIR, name)
        }
    }

    private suspend fun attach(noteId: Long, fileName: String) {
        container.attachmentDao.insert(
            AttachmentEntity(
                recordType = NotesAttachments.RECORD_TYPE,
                recordId = noteId,
                fileName = fileName,
                source = AttachmentSource.SCANNED,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    /**
     * Explicit rather than left to the DB-driven widget collector: that collector runs in its own
     * coroutine, decoupled from the receiver's goAsync() lifecycle, and a batch reply's process can
     * be reclaimed before it observes the new row. Serialized because a batch delivers several
     * replies at once and simultaneous updateAll() calls coalesce into one redraw.
     */
    private suspend fun refreshWidget() {
        widgetUpdateMutex.withLock { NotesWidget().updateAll(context.applicationContext) }
    }

    companion object {
        private val widgetUpdateMutex = Mutex()
    }
}
