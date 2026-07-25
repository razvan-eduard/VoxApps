package com.voxapps.notes.receiver

import android.content.Context
import com.voxapps.attachments.AttachmentDao
import com.voxapps.attachments.AttachmentEntity
import com.voxapps.attachments.AttachmentSource
import com.voxapps.notes.data.Note
import com.voxapps.notes.data.NotesAttachments
import com.voxapps.notes.data.NotesRepository
import com.voxapps.notes.data.preferences.NotesSettings
import com.voxapps.notes.data.preferences.NotesSettingsRepository
import com.voxapps.notes.state.SessionManager
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class NotesExportImportHandlerTest {

    private lateinit var settingsRepo: NotesSettingsRepository
    private lateinit var sessionManager: SessionManager
    private lateinit var notesRepo: NotesRepository
    private lateinit var attachmentDao: AttachmentDao
    private lateinit var handler: NotesExportImportHandler

    @Before
    fun setup() {
        settingsRepo = mockk()
        sessionManager = mockk()
        notesRepo = mockk()
        attachmentDao = mockk(relaxed = true)
        handler = NotesExportImportHandler(mockk<Context>(), settingsRepo, sessionManager, notesRepo, attachmentDao)

        every { settingsRepo.getSnapshot() } returns NotesSettings(isBiometricRequired = false)
        every { notesRepo.categories } returns flowOf(emptyList())
        coEvery { notesRepo.addNote(any(), any(), any(), any()) } returns 1L
        coEvery { notesRepo.deleteNoteById(any()) } just Runs
    }

    private fun note(id: Long, createdAt: Long) = Note(id = id, text = "text $id", createdAt = createdAt)

    @Test
    fun `import only deletes pre-existing notes created at or before exported_at`() = runTest {
        coEvery { notesRepo.notesSnapshot() } returns listOf(
            note(id = 100, createdAt = 500L),
            note(id = 200, createdAt = 2000L)
        )

        val payload = """{"exported_at":1000,"notes":[{"text":"restored"}]}"""
        handler.import(payload)

        coVerify(exactly = 1) { notesRepo.deleteNoteById(100) }
        coVerify(exactly = 0) { notesRepo.deleteNoteById(200) }
    }

    @Test
    fun `import deletes nothing when exported_at is absent`() = runTest {
        coEvery { notesRepo.notesSnapshot() } returns listOf(note(id = 100, createdAt = 500L))

        handler.import("""{"notes":[]}""")

        coVerify(exactly = 0) { notesRepo.deleteNoteById(any()) }
    }

    @Test
    fun `import preserves the imported note's original createdAt`() = runTest {
        coEvery { notesRepo.notesSnapshot() } returns emptyList()

        handler.import("""{"notes":[{"text":"hi","createdAt":42}]}""")

        coVerify(exactly = 1) { notesRepo.addNote(title = null, text = "hi", categoryId = null, createdAt = 42L) }
    }

    @Test
    fun `malformed payload returns a failure result without touching the repository`() = runTest {
        val result = handler.import("{ not json")

        assertFalse(result.ok)
        coVerify(exactly = 0) { notesRepo.notesSnapshot() }
    }

    @Test
    fun `import inserts each nested attachment against the newly created note's id`() = runTest {
        coEvery { notesRepo.notesSnapshot() } returns emptyList()
        coEvery { notesRepo.addNote(any(), any(), any(), any()) } returns 77L

        val payload = """{"notes":[{"text":"hi","attachments":[
            {"fileName":"att_1.jpg","source":"manual","createdAt":10},
            {"fileName":"att_2.jpg","source":"scanned","createdAt":20}
        ]}]}"""
        handler.import(payload)

        coVerify(exactly = 1) {
            attachmentDao.insert(
                AttachmentEntity(recordType = NotesAttachments.RECORD_TYPE, recordId = 77L, fileName = "att_1.jpg", source = AttachmentSource.MANUAL, createdAt = 10L)
            )
        }
        coVerify(exactly = 1) {
            attachmentDao.insert(
                AttachmentEntity(recordType = NotesAttachments.RECORD_TYPE, recordId = 77L, fileName = "att_2.jpg", source = AttachmentSource.SCANNED, createdAt = 20L)
            )
        }
    }

    @Test
    fun `import skips attachments with a blank fileName`() = runTest {
        coEvery { notesRepo.notesSnapshot() } returns emptyList()
        coEvery { notesRepo.addNote(any(), any(), any(), any()) } returns 5L

        handler.import("""{"notes":[{"text":"hi","attachments":[{"fileName":"","source":"manual","createdAt":1}]}]}""")

        coVerify(exactly = 0) { attachmentDao.insert(any()) }
    }

    @Test
    fun `import does not insert attachments when the note insert failed`() = runTest {
        coEvery { notesRepo.notesSnapshot() } returns emptyList()
        coEvery { notesRepo.addNote(any(), any(), any(), any()) } returns 0L

        handler.import("""{"notes":[{"text":"hi","attachments":[{"fileName":"att_1.jpg","source":"manual","createdAt":1}]}]}""")

        coVerify(exactly = 0) { attachmentDao.insert(any()) }
    }
}
