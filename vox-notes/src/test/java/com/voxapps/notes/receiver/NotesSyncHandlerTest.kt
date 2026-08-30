package com.voxapps.notes.receiver

import com.voxapps.datahygiene.SyncLevel
import com.voxapps.ipc.VoxCommand
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxResult
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
import org.junit.Assert.assertTrue
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

    private fun givenSyncLevel(level: SyncLevel) {
        every { settingsRepo.getSnapshot() } returns
            NotesSettings(isBiometricRequired = false, syncLevel = level.name)
    }

    private fun exportCommand(
        since: Long? = null,
        scopeNames: List<String>? = null,
        uids: List<String>? = null,
        cursor: String? = null,
        limit: Int? = null
    ) = VoxCommand(
        op = VoxIpc.OP_SYNC_EXPORT,
        since = since, scopeNames = scopeNames, uids = uids, cursor = cursor, limit = limit
    )

    private fun mergeCommand(payload: String) = VoxCommand(
        op = VoxIpc.OP_SYNC_MERGE,
        text = payload,
        sourceDeviceId = "peer-1",
        sourceDeviceName = "Peer Phone"
    )

    private fun note(uid: String, updatedAt: Long, text: String = "text", categoryId: Long? = null) = Note(
        uid = uid,
        text = text,
        createdAt = updatedAt,
        categoryId = categoryId,
        updatedAt = updatedAt
    )

    private fun exportedUids(result: VoxResult): List<String> =
        JSONObject(result.text).getJSONArray("entries").let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it).getString("uid") }
        }

    // --- export ---

    @Test
    fun `export only includes entries updated after since`() = runTest {
        givenSyncLevel(SyncLevel.ALL)
        coEvery { notesRepo.notesSnapshot() } returns listOf(
            note(uid = "old", updatedAt = 100L),
            note(uid = "new", updatedAt = 2000L)
        )

        val result = handler.export(exportCommand(since = 1000L))

        assertEquals(listOf("new"), exportedUids(result))
    }

    @Test
    fun `export restricts to scopeNames when provided, matching category name case-insensitively`() = runTest {
        givenSyncLevel(SyncLevel.SHARED)
        every { notesRepo.categories } returns flowOf(
            listOf(Category(id = 1, name = "Work", colorArgb = 0, position = 0, createdAt = 0))
        )
        coEvery { notesRepo.notesSnapshot() } returns listOf(
            note(uid = "in-scope", updatedAt = 100L, categoryId = 1),
            note(uid = "uncategorized", updatedAt = 100L, categoryId = null)
        )

        val result = handler.export(exportCommand(scopeNames = listOf("work")))

        assertEquals(listOf("in-scope"), exportedUids(result))
    }

    @Test
    fun `export with an empty scope list sends nothing`() = runTest {
        givenSyncLevel(SyncLevel.SHARED)
        every { notesRepo.categories } returns flowOf(
            listOf(Category(id = 1, name = "Work", colorArgb = 0, position = 0, createdAt = 0))
        )
        coEvery { notesRepo.notesSnapshot() } returns listOf(
            note(uid = "categorized", updatedAt = 100L, categoryId = 1),
            note(uid = "uncategorized", updatedAt = 100L, categoryId = null)
        )

        val result = handler.export(exportCommand(scopeNames = emptyList()))

        assertEquals(emptyList<String>(), exportedUids(result))
    }

    @Test
    fun `export at MANUAL sends only the forced uids and no tombstones`() = runTest {
        coEvery { notesRepo.notesSnapshot() } returns listOf(
            note(uid = "pushed", updatedAt = 100L),
            note(uid = "unpushed", updatedAt = 2000L)
        )
        coEvery { notesRepo.tombstonesSince(any()) } returns listOf(NoteTombstone("deleted-uid", 1500L))

        val result = handler.export(exportCommand(uids = listOf("pushed")))

        assertEquals(listOf("pushed"), exportedUids(result))
        assertEquals(0, JSONObject(result.text).getJSONArray("tombstones").length())
    }

    @Test
    fun `export includes a forced uid even below the since watermark`() = runTest {
        givenSyncLevel(SyncLevel.ALL)
        coEvery { notesRepo.notesSnapshot() } returns listOf(
            note(uid = "stale-but-pushed", updatedAt = 100L),
            note(uid = "new", updatedAt = 2000L)
        )

        val result = handler.export(exportCommand(since = 1000L, uids = listOf("stale-but-pushed")))

        assertEquals(setOf("stale-but-pushed", "new"), exportedUids(result).toSet())
    }

    @Test
    fun `export includes tombstones since the watermark`() = runTest {
        givenSyncLevel(SyncLevel.ALL)
        coEvery { notesRepo.tombstonesSince(1000L) } returns listOf(NoteTombstone("deleted-uid", 1500L))

        val result = handler.export(exportCommand(since = 1000L))
        val tombstone = JSONObject(result.text).getJSONArray("tombstones").getJSONObject(0)

        assertEquals("deleted-uid", tombstone.getString("uid"))
        assertEquals(1500L, tombstone.getLong("deletedAt"))
    }

    @Test
    fun `export while locked returns a failure without touching the repository`() = runTest {
        every { settingsRepo.getSnapshot() } returns NotesSettings(isBiometricRequired = true)
        every { sessionManager.isSessionValid(any()) } returns false

        val result = handler.export(exportCommand())

        assertFalse(result.ok)
        coVerify(exactly = 0) { notesRepo.notesSnapshot() }
    }

    // --- merge ---

    @Test
    fun `merge inserts a remote uid not present locally, stamped with the sender's identity`() = runTest {
        coEvery { notesRepo.insertSyncedNote(any()) } just Runs

        val payload = """{"entries":[{"uid":"a","title":null,"text":"hello","createdAt":100,"updatedAt":100}],"tombstones":[]}"""
        handler.merge(mergeCommand(payload))

        coVerify(exactly = 1) {
            notesRepo.insertSyncedNote(match {
                it.uid == "a" && it.text == "hello" &&
                    it.originDeviceId == "peer-1" && it.originDeviceName == "Peer Phone"
            })
        }
    }

    @Test
    fun `merge updates when the remote updatedAt is newer, preserving the local id and origin`() = runTest {
        coEvery { notesRepo.notesSnapshot() } returns listOf(note(uid = "a", updatedAt = 100L).copy(id = 42))
        coEvery { notesRepo.getIdByUid("a") } returns 42L
        coEvery { notesRepo.updateSyncedNote(any()) } just Runs

        val payload = """{"entries":[{"uid":"a","title":null,"text":"updated","createdAt":100,"updatedAt":200}],"tombstones":[]}"""
        handler.merge(mergeCommand(payload))

        coVerify(exactly = 1) {
            notesRepo.updateSyncedNote(match {
                it.id == 42L && it.uid == "a" && it.text == "updated" && it.originDeviceId == null
            })
        }
        coVerify(exactly = 0) { notesRepo.insertSyncedNote(any()) }
    }

    @Test
    fun `merge keeps the local value for an absent key and nulls out an explicit null`() = runTest {
        coEvery { notesRepo.notesSnapshot() } returns listOf(
            note(uid = "a", updatedAt = 100L).copy(id = 42, title = "Keep", textHtml = "<b>x</b>", categoryId = 5)
        )
        coEvery { notesRepo.getIdByUid("a") } returns 42L
        coEvery { notesRepo.updateSyncedNote(any()) } just Runs

        // No "title"/"categoryName" keys at all; "textHtml" is an explicit null.
        val payload = """{"entries":[{"uid":"a","text":"updated","textHtml":null,"createdAt":100,"updatedAt":200}],"tombstones":[]}"""
        handler.merge(mergeCommand(payload))

        coVerify(exactly = 1) {
            notesRepo.updateSyncedNote(match {
                it.title == "Keep" && it.textHtml == null && it.categoryId == 5L
            })
        }
    }

    @Test
    fun `isStub survives an export-merge round trip`() = runTest {
        givenSyncLevel(SyncLevel.ALL)
        coEvery { notesRepo.notesSnapshot() } returns listOf(
            note(uid = "stub", updatedAt = 100L, text = "").copy(isStub = true)
        )

        val exported = handler.export(exportCommand())
        assertTrue(JSONObject(exported.text).getJSONArray("entries").getJSONObject(0).getBoolean("isStub"))

        coEvery { notesRepo.notesSnapshot() } returns emptyList()
        coEvery { notesRepo.insertSyncedNote(any()) } just Runs
        handler.merge(mergeCommand(exported.text))

        coVerify(exactly = 1) { notesRepo.insertSyncedNote(match { it.uid == "stub" && it.isStub }) }
    }

    @Test
    fun `merge ignores a remote entry that is not newer than the local one`() = runTest {
        coEvery { notesRepo.notesSnapshot() } returns listOf(note(uid = "a", updatedAt = 500L))

        val payload = """{"entries":[{"uid":"a","title":null,"text":"stale","createdAt":100,"updatedAt":100}],"tombstones":[]}"""
        handler.merge(mergeCommand(payload))

        coVerify(exactly = 0) { notesRepo.insertSyncedNote(any()) }
        coVerify(exactly = 0) { notesRepo.updateSyncedNote(any()) }
    }

    @Test
    fun `merge deletes a locally-present uid named in a tombstone`() = runTest {
        coEvery { notesRepo.notesSnapshot() } returns listOf(note(uid = "a", updatedAt = 100L))
        coEvery { notesRepo.deleteNoteByUid("a") } just Runs

        val payload = """{"entries":[],"tombstones":[{"uid":"a","deletedAt":9999}]}"""
        handler.merge(mergeCommand(payload))

        coVerify(exactly = 1) { notesRepo.deleteNoteByUid("a") }
    }

    @Test
    fun `merge resolves an unknown category name by auto-creating it, matching import's convention`() = runTest {
        every { notesRepo.categories } returns flowOf(emptyList())
        coEvery { notesRepo.addCategory(any(), any(), any(), any()) } returns 7L
        coEvery { notesRepo.insertSyncedNote(any()) } just Runs

        val payload = """{"entries":[{"uid":"a","title":null,"text":"hello","createdAt":100,"updatedAt":100,"categoryName":"Travel"}],"tombstones":[]}"""
        handler.merge(mergeCommand(payload))

        coVerify(exactly = 1) { notesRepo.addCategory("Travel", any(), any(), any()) }
        coVerify(exactly = 1) { notesRepo.insertSyncedNote(match { it.categoryId == 7L }) }
    }

    @Test
    fun `merge reports its counts as JSON`() = runTest {
        coEvery { notesRepo.insertSyncedNote(any()) } just Runs

        val payload = """{"entries":[{"uid":"a","title":null,"text":"hello","createdAt":100,"updatedAt":100}],"tombstones":[]}"""
        val result = handler.merge(mergeCommand(payload))

        val counts = JSONObject(result.text)
        assertEquals(1, counts.getInt("inserted"))
        assertEquals(0, counts.getInt("updated"))
        assertEquals(0, counts.getInt("deleted"))
    }

    @Test
    fun `malformed merge payload returns a failure without touching the repository`() = runTest {
        val result = handler.merge(mergeCommand("{ not json"))

        assertFalse(result.ok)
        coVerify(exactly = 0) { notesRepo.notesSnapshot() }
    }

    @Test
    fun `merge while locked returns a failure without touching the repository`() = runTest {
        every { settingsRepo.getSnapshot() } returns NotesSettings(isBiometricRequired = true)
        every { sessionManager.isSessionValid(any()) } returns false

        val result = handler.merge(mergeCommand("""{"entries":[],"tombstones":[]}"""))

        assertFalse(result.ok)
        coVerify(exactly = 0) { notesRepo.notesSnapshot() }
    }
}
