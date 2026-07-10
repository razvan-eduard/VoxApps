package com.voxapps.notes.data

import com.voxapps.notes.testutil.NotesTestDataFactory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class NotesRepositoryMergeCategoriesTest {

    private lateinit var noteDao: NoteDao
    private lateinit var categoryDao: CategoryDao
    private lateinit var repository: NotesRepository

    private val groceries = NotesTestDataFactory.category(id = 1, name = "Groceries")
    private val cumparaturi = NotesTestDataFactory.category(id = 2, name = "Cumpărături")
    private val work = NotesTestDataFactory.category(id = 3, name = "Work")

    @Before
    fun setup() {
        noteDao = mockk(relaxed = true)
        categoryDao = mockk(relaxed = true)
        repository = NotesRepository(noteDao, categoryDao)
        coEvery { categoryDao.observeAll() } returns flowOf(listOf(groceries, cumparaturi, work))
    }

    @Test
    fun `reassigns notes and deletes the old category when both names resolve`() = runTest {
        repository.mergeCategories(mapOf("Groceries" to "Cumpărături"))

        coVerify(exactly = 1) { noteDao.reassignCategory(groceries.id, cumparaturi.id) }
        coVerify(exactly = 1) { categoryDao.delete(groceries) }
    }

    @Test
    fun `is case-insensitive when matching names`() = runTest {
        repository.mergeCategories(mapOf("groceries" to "cumpărături"))

        coVerify(exactly = 1) { noteDao.reassignCategory(groceries.id, cumparaturi.id) }
        coVerify(exactly = 1) { categoryDao.delete(groceries) }
    }

    @Test
    fun `skips entries where the old name does not exist`() = runTest {
        repository.mergeCategories(mapOf("Nonexistent" to "Cumpărături"))

        coVerify(exactly = 0) { noteDao.reassignCategory(any(), any()) }
        coVerify(exactly = 0) { categoryDao.delete(any()) }
    }

    @Test
    fun `skips entries where the canonical name does not exist`() = runTest {
        repository.mergeCategories(mapOf("Groceries" to "Nonexistent"))

        coVerify(exactly = 0) { noteDao.reassignCategory(any(), any()) }
        coVerify(exactly = 0) { categoryDao.delete(any()) }
    }

    @Test
    fun `skips same-name entries (no-op merge)`() = runTest {
        repository.mergeCategories(mapOf("Work" to "Work"))

        coVerify(exactly = 0) { noteDao.reassignCategory(any(), any()) }
        coVerify(exactly = 0) { categoryDao.delete(any()) }
    }

    @Test
    fun `applies multiple independent merge entries`() = runTest {
        repository.mergeCategories(mapOf("Groceries" to "Cumpărături", "Work" to "Cumpărături"))

        coVerify(exactly = 1) { noteDao.reassignCategory(groceries.id, cumparaturi.id) }
        coVerify(exactly = 1) { noteDao.reassignCategory(work.id, cumparaturi.id) }
        coVerify(exactly = 1) { categoryDao.delete(groceries) }
        coVerify(exactly = 1) { categoryDao.delete(work) }
    }
}
