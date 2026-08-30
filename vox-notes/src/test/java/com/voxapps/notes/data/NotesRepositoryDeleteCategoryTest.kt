package com.voxapps.notes.data

import com.voxapps.notes.testutil.NotesTestDataFactory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/** What becomes of the notes when the category they were filed under is deleted. */
class NotesRepositoryDeleteCategoryTest {

    private lateinit var noteDao: NoteDao
    private lateinit var categoryDao: CategoryDao
    private lateinit var repository: NotesRepository

    private val fallback = NotesTestDataFactory.category(id = 1, name = "Uncategorised").copy(isDefault = true)
    private val work = NotesTestDataFactory.category(id = 3, name = "Work")

    @Before
    fun setup() {
        noteDao = mockk(relaxed = true)
        categoryDao = mockk(relaxed = true)
        repository = NotesRepository(noteDao, categoryDao, mockk(relaxed = true), mockk(relaxed = true))
        coEvery { categoryDao.observeAll() } returns flowOf(listOf(fallback, work))
        coEvery { categoryDao.getAll() } returns listOf(fallback, work)
    }

    @Test
    fun `the notes land on the fallback, not on none at all`() = runTest {
        repository.deleteCategory(work)

        coVerify(exactly = 1) { noteDao.reassignCategory(work.id, fallback.id, any()) }
        coVerify(exactly = 0) { noteDao.clearCategory(any(), any()) }
        coVerify(exactly = 1) { categoryDao.delete(work) }
    }

    @Test
    fun `with no fallback stored the notes keep no category`() = runTest {
        coEvery { categoryDao.getAll() } returns listOf(work)

        repository.deleteCategory(work)

        coVerify(exactly = 1) { noteDao.clearCategory(work.id, any()) }
        coVerify(exactly = 0) { noteDao.reassignCategory(any(), any(), any()) }
    }

    @Test
    fun `the fallback itself is never deleted`() = runTest {
        repository.deleteCategory(fallback)

        coVerify(exactly = 0) { categoryDao.delete(any()) }
        coVerify(exactly = 0) { noteDao.reassignCategory(any(), any(), any()) }
        coVerify(exactly = 0) { noteDao.clearCategory(any(), any()) }
    }

    /** Exactly one carries it: moving the star clears it everywhere else in the same pass. */
    @Test
    fun `moving the star leaves it in one place`() = runTest {
        repository.setDefaultCategory(work.id)

        coVerify(exactly = 1) { categoryDao.update(match { it.id == work.id && it.isDefault }) }
        coVerify(exactly = 1) { categoryDao.update(match { it.id == fallback.id && !it.isDefault }) }
    }
}
