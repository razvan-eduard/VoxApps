package com.voxapps.notes.data

import com.voxapps.notes.domain.llm.DuplicateGroup
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class NotesRepositoryApplyDeduplicationTest {

    private lateinit var noteDao: NoteDao
    private lateinit var categoryDao: CategoryDao
    private lateinit var repository: NotesRepository

    @Before
    fun setup() {
        noteDao = mockk(relaxed = true)
        categoryDao = mockk(relaxed = true)
        repository = NotesRepository(noteDao, categoryDao)
    }

    @Test
    fun `deletes every duplicate id except keepId`() = runTest {
        repository.applyNoteDeduplication(listOf(DuplicateGroup(keepId = 12, duplicateIds = listOf(7, 9))))

        coVerify(exactly = 1) { noteDao.deleteById(7) }
        coVerify(exactly = 1) { noteDao.deleteById(9) }
        coVerify(exactly = 0) { noteDao.deleteById(12) }
    }

    @Test
    fun `does not delete keepId even if redundantly listed as its own duplicate`() = runTest {
        repository.applyNoteDeduplication(listOf(DuplicateGroup(keepId = 12, duplicateIds = listOf(12, 7))))

        coVerify(exactly = 1) { noteDao.deleteById(7) }
        coVerify(exactly = 0) { noteDao.deleteById(12) }
    }

    @Test
    fun `applies multiple independent groups`() = runTest {
        repository.applyNoteDeduplication(
            listOf(
                DuplicateGroup(keepId = 1, duplicateIds = listOf(2)),
                DuplicateGroup(keepId = 10, duplicateIds = listOf(11, 12))
            )
        )

        coVerify(exactly = 1) { noteDao.deleteById(2) }
        coVerify(exactly = 1) { noteDao.deleteById(11) }
        coVerify(exactly = 1) { noteDao.deleteById(12) }
        coVerify(exactly = 0) { noteDao.deleteById(1) }
        coVerify(exactly = 0) { noteDao.deleteById(10) }
    }

    @Test
    fun `empty groups list deletes nothing`() = runTest {
        repository.applyNoteDeduplication(emptyList())

        coVerify(exactly = 0) { noteDao.deleteById(any()) }
    }
}
