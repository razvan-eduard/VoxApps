package com.voxapps.notes.state

import app.cash.turbine.test
import com.voxapps.notes.data.Category
import com.voxapps.notes.data.NoteWithCategory
import com.voxapps.notes.data.NotesRepository
import com.voxapps.notes.data.preferences.NotesSettings
import com.voxapps.notes.data.preferences.NotesSettingsRepository
import com.voxapps.notes.domain.llm.NoteDeduplicationRepository
import com.voxapps.notes.testutil.NotesTestDataFactory
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotesStateManagerTest {

    private lateinit var settingsRepo: NotesSettingsRepository
    private lateinit var notesRepo: NotesRepository
    private lateinit var settingsFlow: MutableStateFlow<NotesSettings>
    private lateinit var notesFlow: MutableStateFlow<List<NoteWithCategory>>
    private lateinit var categoriesFlow: MutableStateFlow<List<Category>>
    private var now = 1_000_000L
    private lateinit var sessionManager: SessionManager
    private lateinit var noteDeduplicationRepo: NoteDeduplicationRepository

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        settingsFlow = MutableStateFlow(NotesSettings())
        notesFlow = MutableStateFlow(emptyList())
        categoriesFlow = MutableStateFlow(emptyList())

        settingsRepo = mockk(relaxed = true)
        every { settingsRepo.settingsFlow } returns settingsFlow
        every { settingsRepo.getSnapshot() } answers { settingsFlow.value }

        notesRepo = mockk(relaxed = true)
        every { notesRepo.notesWithCategory } returns notesFlow
        every { notesRepo.categories } returns categoriesFlow

        sessionManager = SessionManager(clock = { now })
        noteDeduplicationRepo = mockk(relaxed = true)
        every { noteDeduplicationRepo.pendingGroupsFlow } returns MutableStateFlow(emptyList())
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun manager() = NotesStateManager(settingsRepo, notesRepo, sessionManager, noteDeduplicationRepo)

    @Test
    fun `category filter narrows the visible notes`() = runTest {
        val cat = NotesTestDataFactory.category(id = 7)
        notesFlow.value = listOf(
            NotesTestDataFactory.noteWithCategory(NotesTestDataFactory.note(id = 1, categoryId = 7), cat),
            NotesTestDataFactory.noteWithCategory(NotesTestDataFactory.note(id = 2, categoryId = null))
        )
        val sm = manager()

        sm.setCategoryFilter(7)

        val state = sm.uiState.value as NotesUiState.Unlocked
        assertEquals(listOf(1L), state.notes.map { it.note.id })
    }

    @Test
    fun `sort switches ordering`() = runTest {
        notesFlow.value = listOf(
            NotesTestDataFactory.noteWithCategory(NotesTestDataFactory.note(id = 1, createdAt = 100)),
            NotesTestDataFactory.noteWithCategory(NotesTestDataFactory.note(id = 2, createdAt = 200))
        )
        val sm = manager()

        sm.setSort(SortMode.OLDEST)
        assertEquals(listOf(1L, 2L), (sm.uiState.value as NotesUiState.Unlocked).notes.map { it.note.id })

        sm.setSort(SortMode.NEWEST)
        assertEquals(listOf(2L, 1L), (sm.uiState.value as NotesUiState.Unlocked).notes.map { it.note.id })
    }

    @Test
    fun `expired biometric session yields Locked, unlock returns Unlocked`() = runTest {
        settingsFlow.value = NotesSettings(isBiometricRequired = true, sessionTimeoutMinutes = 30)
        val sm = manager()

        sm.uiState.test {
            assertTrue(awaitItem() is NotesUiState.Locked) // never unlocked -> locked
            sm.unlock()
            assertTrue(awaitItem() is NotesUiState.Unlocked)
        }
    }

    @Test
    fun `removing the selected category resets the filter`() = runTest {
        val cat = NotesTestDataFactory.category(id = 5)
        categoriesFlow.value = listOf(cat)
        val sm = manager()
        sm.setCategoryFilter(5)
        assertEquals(5L, (sm.uiState.value as NotesUiState.Unlocked).selectedCategoryId)

        sm.removeCategory(cat)

        assertNull((sm.uiState.value as NotesUiState.Unlocked).selectedCategoryId)
    }
}
