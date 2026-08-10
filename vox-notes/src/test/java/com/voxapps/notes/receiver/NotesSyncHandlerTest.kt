package com.voxapps.notes.receiver

import com.voxapps.notes.data.Category
import com.voxapps.notes.data.Note
import com.voxapps.notes.data.NoteTombstone
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
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class NotesSyncHandlerTest {

    private lateinit var settingsRepo: NotesSettingsRepository
    private lateinit var sessionManager: SessionManager
    private lateinit var notesRepo: NotesRepository
    private lateinit var handler: NotesSyncHandler

    @Before
    fun setup() {
        settingsRepo = mockk()
        sessionManager = mockk()
        notesRepo = mockk()
        handler = NotesSyncHandler(settingsRepo, sessionManager, notesRepo, "The notes are locked. Unlock the app.")

        every { settingsRepo.getSnapshot() } returns NotesSettings(isBiometricRequired = false)
        every { notesRepo.categories } returns flowOf(emptyList())
        coEvery { notesRepo.notesSnapshot() } returns emptyList()
        coEvery { notesRepo.tombstonesSince(any()) } returns emptyList()
    }

    private fun note(uid: String, updatedAt: Long, text: String = "text", categoryId: Long? = null) = Note(
        uid = uid,
        text = text,
        createdAt = updatedAt,
        categoryId = categoryId,
        updatedAt = updatedAt
    )

    // --- export ---

    @Test
    fun `export only includes entries updated after since`() = runTest {
        coEvery { notesRepo.notesSnapshot() } returns listOf(
            note(uid = "old", updatedAt = 100L),
            note(uid = "new", updatedAt = 2000L)
        )

        val result = handler.export(since = 1000L, scopeNames = null)
        val uids = JSONObject(result.text).getJSONArray("entries").let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it).getString("uid") }
        }

        assertEquals(listOf("new"), uids)
    }

    @Test
    fun `export restricts to scopeNames when provided, matching category name case-insensitively`() = runTest {
        every { notesRepo.categories } returns flowOf(
            listOf(Category(id = 1, name = "Work", colorArgb = 0, position = 0, createdAt = 0))
        )
        coEvery { notesRepo.notesSnapshot() } returns listOf(
            note(uid = "in-scope", updatedAt = 100L, categoryId = 1),
            note(uid = "uncategorized", updatedAt = 100L, categoryId = null)
        )

        val result = handler.export(since = 0L, scopeNames = listOf("work"))
        val uids = JSONObject(result.text).getJSONArray("entries").let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it).getString("uid") }
        }

        assertEquals(listOf("in-scope"), uids)
    }

    @Test
    fun `export includes tombstones since the watermark`() = runTest {
        coEvery { notesRepo.tombstonesSince(1000L) } returns listOf(NoteTombstone("deleted-uid", 1500L))

        val result = handler.export(since = 1000L, scopeNames = null)
        val tombstone = JSONObject(result.text).getJSONArray("tombstones").getJSONObject(0)

        assertEquals("deleted-uid", tombstone.getString("uid"))
        assertEquals(1500L, tombstone.getLong("deletedAt"))
    }

    @Test
    fun `export while locked returns a failure without touching the repository`() = runTest {
        every { settingsRepo.getSnapshot() } returns NotesSettings(isBiometricRequired = true)
        every { sessionManager.isSessionValid(any()) } returns false

        val result = handler.export(since = 0L, scopeNames = null)

        assertFalse(result.ok)
        coVerify(exactly = 0) { notesRepo.notesSnapshot() }
    }

    // --- merge ---

    @Test
    fun `merge inserts a remote uid not present locally`() = runTest {
        coEvery { notesRepo.insertSyncedNote(any()) } just Runs

        val payload = """{"entries":[{"uid":"a","title":null,"text":"hello","createdAt":100,"updatedAt":100}],"tombstones":[]}"""
        handler.merge(payload)

        coVerify(exactly = 1) { notesRepo.insertSyncedNote(match { it.uid == "a" && it.text == "hello" }) }
    }

    @Test
    fun `merge updates when the remote updatedAt is newer, preserving the local id`() = runTest {
        coEvery { notesRepo.notesSnapshot() } returns listOf(note(uid = "a", updatedAt = 100L).copy(id = 42))
        coEvery { notesRepo.getIdByUid("a") } returns 42L
        coEvery { notesRepo.updateSyncedNote(any()) } just Runs

        val payload = """{"entries":[{"uid":"a","title":null,"text":"updated","createdAt":100,"updatedAt":200}],"tombstones":[]}"""
        handler.merge(payload)

        coVerify(exactly = 1) { notesRepo.updateSyncedNote(match { it.id == 42L && it.uid == "a" && it.text == "updated" }) }
        coVerify(exactly = 0) { notesRepo.insertSyncedNote(any()) }
    }

    @Test
    fun `merge ignores a remote entry that is not newer than the local one`() = runTest {
        coEvery { notesRepo.notesSnapshot() } returns listOf(note(uid = "a", updatedAt = 500L))

        val payload = """{"entries":[{"uid":"a","title":null,"text":"stale","createdAt":100,"updatedAt":100}],"tombstones":[]}"""
        handler.merge(payload)

        coVerify(exactly = 0) { notesRepo.insertSyncedNote(any()) }
        coVerify(exactly = 0) { notesRepo.updateSyncedNote(any()) }
    }

    @Test
    fun `merge deletes a locally-present uid named in a tombstone`() = runTest {
        coEvery { notesRepo.notesSnapshot() } returns listOf(note(uid = "a", updatedAt = 100L))
        coEvery { notesRepo.deleteNoteByUid("a") } just Runs

        val payload = """{"entries":[],"tombstones":[{"uid":"a","deletedAt":9999}]}"""
        handler.merge(payload)

        coVerify(exactly = 1) { notesRepo.deleteNoteByUid("a") }
    }

    @Test
    fun `merge resolves an unknown category name by auto-creating it, matching import's convention`() = runTest {
        every { notesRepo.categories } returns flowOf(emptyList())
        coEvery { notesRepo.addCategory(any(), any(), any(), any()) } returns 7L
        coEvery { notesRepo.insertSyncedNote(any()) } just Runs

        val payload = """{"entries":[{"uid":"a","title":null,"text":"hello","createdAt":100,"updatedAt":100,"categoryName":"Travel"}],"tombstones":[]}"""
        handler.merge(payload)

        coVerify(exactly = 1) { notesRepo.addCategory("Travel", any(), any(), any()) }
        coVerify(exactly = 1) { notesRepo.insertSyncedNote(match { it.categoryId == 7L }) }
    }

    @Test
    fun `malformed merge payload returns a failure without touching the repository`() = runTest {
        val result = handler.merge("{ not json")

        assertFalse(result.ok)
        coVerify(exactly = 0) { notesRepo.notesSnapshot() }
    }

    @Test
    fun `merge while locked returns a failure without touching the repository`() = runTest {
        every { settingsRepo.getSnapshot() } returns NotesSettings(isBiometricRequired = true)
        every { sessionManager.isSessionValid(any()) } returns false

        val result = handler.merge("""{"entries":[],"tombstones":[]}""")

        assertFalse(result.ok)
        coVerify(exactly = 0) { notesRepo.notesSnapshot() }
    }
}
