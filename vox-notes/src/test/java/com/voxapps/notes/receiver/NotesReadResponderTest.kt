package com.voxapps.notes.receiver

import com.voxapps.notes.data.NotesRepository
import com.voxapps.notes.data.preferences.NotesSettingsRepository
import com.voxapps.notes.state.SessionManager
import com.voxapps.notes.testutil.NotesTestDataFactory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesReadResponderTest {

    private val settingsRepo = mockk<NotesSettingsRepository>()
    private val sessionManager = mockk<SessionManager>()
    private val notesRepo = mockk<NotesRepository>()
    private val responder = NotesReadResponder(settingsRepo, sessionManager, notesRepo)

    @Test
    fun `locked read never touches the DB and returns the spoken message`() = runTest {
        every { settingsRepo.getSnapshot() } returns NotesTestDataFactory.settings(isBiometricRequired = true)
        every { sessionManager.isSessionValid(any()) } returns false

        val result = responder.respond()

        assertFalse(result.ok)
        assertEquals(NotesReadResponder.LOCKED_MESSAGE, result.text)
        coVerify(exactly = 0) { notesRepo.notesSnapshot() }
    }

    @Test
    fun `open mode returns joined notes without a biometric check`() = runTest {
        every { settingsRepo.getSnapshot() } returns NotesTestDataFactory.settings(isBiometricRequired = false)
        coEvery { notesRepo.notesSnapshot() } returns listOf(
            NotesTestDataFactory.note(id = 1, title = "T", text = "a"),
            NotesTestDataFactory.note(id = 2, text = "b")
        )

        val result = responder.respond()

        assertTrue(result.ok)
        assertEquals("T: a\nb", result.text)
    }

    @Test
    fun `valid session in biometric mode reads notes`() = runTest {
        every { settingsRepo.getSnapshot() } returns NotesTestDataFactory.settings(isBiometricRequired = true)
        every { sessionManager.isSessionValid(any()) } returns true
        coEvery { notesRepo.notesSnapshot() } returns listOf(NotesTestDataFactory.note(text = "x"))

        val result = responder.respond()

        assertTrue(result.ok)
        assertEquals("x", result.text)
        coVerify(exactly = 1) { notesRepo.notesSnapshot() }
    }
}
