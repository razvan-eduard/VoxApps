package com.voxapps.calendarapp.receiver

import com.voxapps.calendarapp.data.CalendarEntry
import com.voxapps.calendarapp.data.CalendarEntryTag
import com.voxapps.calendarapp.data.CalendarEntryTombstone
import com.voxapps.calendarapp.data.CalendarEntryType
import com.voxapps.calendarapp.data.CalendarEntryWithTags
import com.voxapps.calendarapp.data.CalendarLayer
import com.voxapps.calendarapp.data.CalendarRepository
import com.voxapps.calendarapp.data.ToDoRepository
import com.voxapps.calendarapp.data.preferences.CalendarSettings
import com.voxapps.calendarapp.data.preferences.CalendarSettingsRepository
import com.voxapps.calendarapp.state.SessionManager
import com.voxapps.ipc.VoxCommand
import com.voxapps.ipc.VoxIpc
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

class CalendarSyncHandlerTest {

    private lateinit var settingsRepo: CalendarSettingsRepository
    private lateinit var sessionManager: SessionManager
    private lateinit var calendarRepo: CalendarRepository
    private lateinit var toDoRepo: ToDoRepository
    private lateinit var handler: CalendarSyncHandler

    @Before
    fun setup() {
        settingsRepo = mockk()
        sessionManager = mockk()
        calendarRepo = mockk()
        toDoRepo = mockk()
        handler = CalendarSyncHandler(settingsRepo, sessionManager, calendarRepo, toDoRepo, "The calendar is locked. Unlock the app.")

        every { settingsRepo.getSnapshot() } returns CalendarSettings(isBiometricRequired = false)
        every { calendarRepo.layers } returns flowOf(emptyList())
        every { toDoRepo.lists } returns flowOf(emptyList())
        coEvery { calendarRepo.entriesSnapshot() } returns emptyList()
        coEvery { calendarRepo.tombstonesSince(any()) } returns emptyList()
    }

    private fun exportCommand(since: Long? = null, scopeNames: List<String>? = null, uids: List<String>? = null) =
        VoxCommand(op = VoxIpc.OP_SYNC_EXPORT, since = since, scopeNames = scopeNames, uids = uids)

    private fun mergeCommand(payload: String, deviceId: String? = null, deviceName: String? = null) =
        VoxCommand(op = VoxIpc.OP_SYNC_MERGE, text = payload, sourceDeviceId = deviceId, sourceDeviceName = deviceName)

    private fun entry(uid: String, updatedAt: Long, title: String = "title", layerId: Long = 1) = CalendarEntry(
        uid = uid,
        type = CalendarEntryType.EVENT,
        title = title,
        startMillis = 0L,
        layerId = layerId,
        createdAt = updatedAt,
        updatedAt = updatedAt
    )

    private fun withTags(entry: CalendarEntry, tags: List<String> = emptyList()) = CalendarEntryWithTags(
        entry,
        tags.map { name -> CalendarEntryTag(entryId = entry.id, tagName = name) }
    )

    private fun exportedUids(resultText: String?): List<String> =
        JSONObject(resultText).getJSONArray("entries").let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it).getString("uid") }
        }

    // --- export ---

    @Test
    fun `export only includes entries updated after since`() = runTest {
        coEvery { calendarRepo.entriesSnapshot() } returns listOf(
            withTags(entry(uid = "old", updatedAt = 100L)),
            withTags(entry(uid = "new", updatedAt = 2000L))
        )

        val result = handler.export(exportCommand(since = 1000L))

        assertEquals(listOf("new"), exportedUids(result.text))
    }

    @Test
    fun `export restricts to scopeNames when provided, matching layer name case-insensitively`() = runTest {
        every { calendarRepo.layers } returns flowOf(
            listOf(CalendarLayer(id = 1, name = "Work", colorArgb = 0, createdAt = 0))
        )
        coEvery { calendarRepo.entriesSnapshot() } returns listOf(
            withTags(entry(uid = "in-scope", updatedAt = 100L, layerId = 1)),
            withTags(entry(uid = "other-layer", updatedAt = 100L, layerId = 2))
        )

        val result = handler.export(exportCommand(since = 0L, scopeNames = listOf("work")))

        assertEquals(listOf("in-scope"), exportedUids(result.text))
    }

    @Test
    fun `export with an empty scope list exports no entries at all`() = runTest {
        every { calendarRepo.layers } returns flowOf(
            listOf(CalendarLayer(id = 1, name = "Work", colorArgb = 0, createdAt = 0))
        )
        coEvery { calendarRepo.entriesSnapshot() } returns listOf(
            withTags(entry(uid = "changed", updatedAt = 100L, layerId = 1))
        )

        val result = handler.export(exportCommand(since = 0L, scopeNames = emptyList()))

        assertEquals(emptyList<String>(), exportedUids(result.text))
    }

    @Test
    fun `forced uids travel despite an empty scope and an already-passed watermark`() = runTest {
        every { calendarRepo.layers } returns flowOf(
            listOf(CalendarLayer(id = 1, name = "Work", colorArgb = 0, createdAt = 0))
        )
        coEvery { calendarRepo.entriesSnapshot() } returns listOf(
            withTags(entry(uid = "pushed", updatedAt = 100L, layerId = 1)),
            withTags(entry(uid = "unrelated", updatedAt = 100L, layerId = 1))
        )

        val result = handler.export(exportCommand(since = 5000L, scopeNames = emptyList(), uids = listOf("pushed")))

        assertEquals(listOf("pushed"), exportedUids(result.text))
    }

    @Test
    fun `export includes tombstones since the watermark`() = runTest {
        coEvery { calendarRepo.tombstonesSince(1000L) } returns listOf(CalendarEntryTombstone("deleted-uid", 1500L))

        val result = handler.export(exportCommand(since = 1000L))
        val tombstone = JSONObject(result.text).getJSONArray("tombstones").getJSONObject(0)

        assertEquals("deleted-uid", tombstone.getString("uid"))
        assertEquals(1500L, tombstone.getLong("deletedAt"))
    }

    @Test
    fun `export while locked returns a failure without touching the repository`() = runTest {
        every { settingsRepo.getSnapshot() } returns CalendarSettings(isBiometricRequired = true)
        every { sessionManager.isSessionValid(any()) } returns false

        val result = handler.export(exportCommand(since = 0L))

        assertFalse(result.ok)
        coVerify(exactly = 0) { calendarRepo.entriesSnapshot() }
    }

    // --- merge ---

    @Test
    fun `merge inserts a remote uid not present locally`() = runTest {
        coEvery { calendarRepo.insertSyncedEntry(any(), any()) } returns 1L

        val payload = """{"entries":[{"uid":"a","type":"EVENT","title":"hi","startMillis":0,"createdAt":100,"updatedAt":100}],"tombstones":[]}"""
        handler.merge(mergeCommand(payload))

        coVerify(exactly = 1) { calendarRepo.insertSyncedEntry(match { it.uid == "a" && it.title == "hi" }, any()) }
    }

    @Test
    fun `merge updates when the remote updatedAt is newer, preserving the local id`() = runTest {
        coEvery { calendarRepo.entriesSnapshot() } returns listOf(withTags(entry(uid = "a", updatedAt = 100L).copy(id = 42)))
        coEvery { calendarRepo.getIdByUid("a") } returns 42L
        coEvery { calendarRepo.updateSyncedEntry(any(), any()) } just Runs

        val payload = """{"entries":[{"uid":"a","type":"EVENT","title":"updated","startMillis":0,"createdAt":100,"updatedAt":200}],"tombstones":[]}"""
        handler.merge(mergeCommand(payload))

        coVerify(exactly = 1) { calendarRepo.updateSyncedEntry(match { it.id == 42L && it.uid == "a" && it.title == "updated" }, any()) }
        coVerify(exactly = 0) { calendarRepo.insertSyncedEntry(any(), any()) }
    }

    @Test
    fun `merge ignores a remote entry that is not newer than the local one`() = runTest {
        coEvery { calendarRepo.entriesSnapshot() } returns listOf(withTags(entry(uid = "a", updatedAt = 500L)))

        val payload = """{"entries":[{"uid":"a","type":"EVENT","title":"stale","startMillis":0,"createdAt":100,"updatedAt":100}],"tombstones":[]}"""
        handler.merge(mergeCommand(payload))

        coVerify(exactly = 0) { calendarRepo.insertSyncedEntry(any(), any()) }
        coVerify(exactly = 0) { calendarRepo.updateSyncedEntry(any(), any()) }
    }

    @Test
    fun `merge deletes a locally-present uid named in a tombstone`() = runTest {
        coEvery { calendarRepo.entriesSnapshot() } returns listOf(withTags(entry(uid = "a", updatedAt = 100L)))
        coEvery { calendarRepo.deleteEntryByUid("a") } just Runs

        val payload = """{"entries":[],"tombstones":[{"uid":"a","deletedAt":9999}]}"""
        handler.merge(mergeCommand(payload))

        coVerify(exactly = 1) { calendarRepo.deleteEntryByUid("a") }
    }

    @Test
    fun `merge resolves an unknown layer name by auto-creating it, matching import's convention`() = runTest {
        every { calendarRepo.layers } returns flowOf(emptyList())
        coEvery { calendarRepo.addLayer(any(), any(), any(), any()) } returns 7L
        coEvery { calendarRepo.insertSyncedEntry(any(), any()) } returns 1L

        val payload = """{"entries":[{"uid":"a","type":"EVENT","title":"hi","startMillis":0,"createdAt":100,"updatedAt":100,"layerName":"Travel"}],"tombstones":[]}"""
        handler.merge(mergeCommand(payload))

        coVerify(exactly = 1) { calendarRepo.addLayer("Travel", any(), any(), any()) }
        coVerify(exactly = 1) { calendarRepo.insertSyncedEntry(match { it.layerId == 7L }, any()) }
    }

    @Test
    fun `merge resolves an unknown list name by creating it through the app's own create flow`() = runTest {
        coEvery { toDoRepo.createList("Groceries", any()) } returns 9L
        coEvery { calendarRepo.insertSyncedEntry(any(), any()) } returns 1L

        val payload = """{"entries":[{"uid":"a","type":"TASK","title":"milk","listName":"Groceries","createdAt":100,"updatedAt":100}],"tombstones":[]}"""
        handler.merge(mergeCommand(payload))

        coVerify(exactly = 1) { toDoRepo.createList("Groceries", any()) }
        coVerify(exactly = 1) { calendarRepo.insertSyncedEntry(match { it.listId == 9L }, any()) }
    }

    @Test
    fun `absent keys keep the local row's list link, importance, and tags`() = runTest {
        val localRow = entry(uid = "a", updatedAt = 100L).copy(id = 42, listId = 5L, isImportant = true)
        coEvery { calendarRepo.entriesSnapshot() } returns listOf(withTags(localRow, tags = listOf("keep")))
        coEvery { calendarRepo.getIdByUid("a") } returns 42L
        coEvery { calendarRepo.updateSyncedEntry(any(), any()) } just Runs

        // A narrower delta, as an older peer would send: no listName/isImportant/tags keys at all.
        val payload = """{"entries":[{"uid":"a","type":"EVENT","title":"t","startMillis":0,"createdAt":100,"updatedAt":200}],"tombstones":[]}"""
        handler.merge(mergeCommand(payload))

        coVerify(exactly = 1) {
            calendarRepo.updateSyncedEntry(match { it.listId == 5L && it.isImportant }, isNull())
        }
    }

    @Test
    fun `round-trip - exported explicit nulls overwrite where absent keys would preserve`() = runTest {
        // The peer's version of "a" belongs to no list and is flagged important.
        coEvery { calendarRepo.entriesSnapshot() } returns listOf(
            withTags(entry(uid = "a", updatedAt = 200L).copy(isImportant = true))
        )
        val exported = handler.export(exportCommand()).text.orEmpty()
        val exportedEntry = JSONObject(exported).getJSONArray("entries").getJSONObject(0)
        assertTrue(exportedEntry.has("listName") && exportedEntry.isNull("listName"))

        // Merging that delta onto a device whose local row is list-linked and unimportant.
        val localRow = entry(uid = "a", updatedAt = 100L).copy(id = 42, listId = 5L, isImportant = false)
        coEvery { calendarRepo.entriesSnapshot() } returns listOf(withTags(localRow))
        coEvery { calendarRepo.getIdByUid("a") } returns 42L
        coEvery { calendarRepo.updateSyncedEntry(any(), any()) } just Runs
        handler.merge(mergeCommand(exported))

        coVerify(exactly = 1) {
            calendarRepo.updateSyncedEntry(match { it.listId == null && it.isImportant }, any())
        }
    }

    @Test
    fun `merge stamps the sender's identity on inserts and never on updates`() = runTest {
        coEvery { calendarRepo.entriesSnapshot() } returns listOf(
            withTags(entry(uid = "existing", updatedAt = 100L).copy(id = 7))
        )
        coEvery { calendarRepo.getIdByUid("existing") } returns 7L
        coEvery { calendarRepo.insertSyncedEntry(any(), any()) } returns 1L
        coEvery { calendarRepo.updateSyncedEntry(any(), any()) } just Runs

        val payload = """{"entries":[
            {"uid":"fresh","type":"EVENT","title":"n","startMillis":0,"createdAt":1,"updatedAt":1},
            {"uid":"existing","type":"EVENT","title":"e","startMillis":0,"createdAt":1,"updatedAt":200}
        ],"tombstones":[]}"""
        handler.merge(mergeCommand(payload, deviceId = "peer-1", deviceName = "Pixel"))

        coVerify(exactly = 1) {
            calendarRepo.insertSyncedEntry(
                match { it.uid == "fresh" && it.originDeviceId == "peer-1" && it.originDeviceName == "Pixel" },
                any()
            )
        }
        coVerify(exactly = 1) {
            calendarRepo.updateSyncedEntry(match { it.uid == "existing" && it.originDeviceId == null }, any())
        }
    }

    @Test
    fun `malformed merge payload returns a failure without touching the repository`() = runTest {
        val result = handler.merge(mergeCommand("{ not json"))

        assertFalse(result.ok)
        coVerify(exactly = 0) { calendarRepo.entriesSnapshot() }
    }

    @Test
    fun `merge while locked returns a failure without touching the repository`() = runTest {
        every { settingsRepo.getSnapshot() } returns CalendarSettings(isBiometricRequired = true)
        every { sessionManager.isSessionValid(any()) } returns false

        val result = handler.merge(mergeCommand("""{"entries":[],"tombstones":[]}"""))

        assertFalse(result.ok)
        coVerify(exactly = 0) { calendarRepo.entriesSnapshot() }
    }
}
