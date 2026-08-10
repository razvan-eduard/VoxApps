package com.voxapps.calendarapp.receiver

import com.voxapps.calendarapp.data.CalendarEntry
import com.voxapps.calendarapp.data.CalendarEntryTag
import com.voxapps.calendarapp.data.CalendarEntryTombstone
import com.voxapps.calendarapp.data.CalendarEntryType
import com.voxapps.calendarapp.data.CalendarEntryWithTags
import com.voxapps.calendarapp.data.CalendarLayer
import com.voxapps.calendarapp.data.CalendarRepository
import com.voxapps.calendarapp.data.preferences.CalendarSettings
import com.voxapps.calendarapp.data.preferences.CalendarSettingsRepository
import com.voxapps.calendarapp.state.SessionManager
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

class CalendarSyncHandlerTest {

    private lateinit var settingsRepo: CalendarSettingsRepository
    private lateinit var sessionManager: SessionManager
    private lateinit var calendarRepo: CalendarRepository
    private lateinit var handler: CalendarSyncHandler

    @Before
    fun setup() {
        settingsRepo = mockk()
        sessionManager = mockk()
        calendarRepo = mockk()
        handler = CalendarSyncHandler(settingsRepo, sessionManager, calendarRepo, "The calendar is locked. Unlock the app.")

        every { settingsRepo.getSnapshot() } returns CalendarSettings(isBiometricRequired = false)
        every { calendarRepo.layers } returns flowOf(emptyList())
        coEvery { calendarRepo.entriesSnapshot() } returns emptyList()
        coEvery { calendarRepo.tombstonesSince(any()) } returns emptyList()
    }

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

    // --- export ---

    @Test
    fun `export only includes entries updated after since`() = runTest {
        coEvery { calendarRepo.entriesSnapshot() } returns listOf(
            withTags(entry(uid = "old", updatedAt = 100L)),
            withTags(entry(uid = "new", updatedAt = 2000L))
        )

        val result = handler.export(since = 1000L, scopeNames = null)
        val uids = JSONObject(result.text).getJSONArray("entries").let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it).getString("uid") }
        }

        assertEquals(listOf("new"), uids)
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

        val result = handler.export(since = 0L, scopeNames = listOf("work"))
        val uids = JSONObject(result.text).getJSONArray("entries").let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it).getString("uid") }
        }

        assertEquals(listOf("in-scope"), uids)
    }

    @Test
    fun `export includes tombstones since the watermark`() = runTest {
        coEvery { calendarRepo.tombstonesSince(1000L) } returns listOf(CalendarEntryTombstone("deleted-uid", 1500L))

        val result = handler.export(since = 1000L, scopeNames = null)
        val tombstone = JSONObject(result.text).getJSONArray("tombstones").getJSONObject(0)

        assertEquals("deleted-uid", tombstone.getString("uid"))
        assertEquals(1500L, tombstone.getLong("deletedAt"))
    }

    @Test
    fun `export while locked returns a failure without touching the repository`() = runTest {
        every { settingsRepo.getSnapshot() } returns CalendarSettings(isBiometricRequired = true)
        every { sessionManager.isSessionValid(any()) } returns false

        val result = handler.export(since = 0L, scopeNames = null)

        assertFalse(result.ok)
        coVerify(exactly = 0) { calendarRepo.entriesSnapshot() }
    }

    // --- merge ---

    @Test
    fun `merge inserts a remote uid not present locally`() = runTest {
        coEvery { calendarRepo.insertSyncedEntry(any(), any()) } returns 1L

        val payload = """{"entries":[{"uid":"a","type":"EVENT","title":"hi","startMillis":0,"createdAt":100,"updatedAt":100}],"tombstones":[]}"""
        handler.merge(payload)

        coVerify(exactly = 1) { calendarRepo.insertSyncedEntry(match { it.uid == "a" && it.title == "hi" }, any()) }
    }

    @Test
    fun `merge updates when the remote updatedAt is newer, preserving the local id`() = runTest {
        coEvery { calendarRepo.entriesSnapshot() } returns listOf(withTags(entry(uid = "a", updatedAt = 100L).copy(id = 42)))
        coEvery { calendarRepo.getIdByUid("a") } returns 42L
        coEvery { calendarRepo.updateSyncedEntry(any(), any()) } just Runs

        val payload = """{"entries":[{"uid":"a","type":"EVENT","title":"updated","startMillis":0,"createdAt":100,"updatedAt":200}],"tombstones":[]}"""
        handler.merge(payload)

        coVerify(exactly = 1) { calendarRepo.updateSyncedEntry(match { it.id == 42L && it.uid == "a" && it.title == "updated" }, any()) }
        coVerify(exactly = 0) { calendarRepo.insertSyncedEntry(any(), any()) }
    }

    @Test
    fun `merge ignores a remote entry that is not newer than the local one`() = runTest {
        coEvery { calendarRepo.entriesSnapshot() } returns listOf(withTags(entry(uid = "a", updatedAt = 500L)))

        val payload = """{"entries":[{"uid":"a","type":"EVENT","title":"stale","startMillis":0,"createdAt":100,"updatedAt":100}],"tombstones":[]}"""
        handler.merge(payload)

        coVerify(exactly = 0) { calendarRepo.insertSyncedEntry(any(), any()) }
        coVerify(exactly = 0) { calendarRepo.updateSyncedEntry(any(), any()) }
    }

    @Test
    fun `merge deletes a locally-present uid named in a tombstone`() = runTest {
        coEvery { calendarRepo.entriesSnapshot() } returns listOf(withTags(entry(uid = "a", updatedAt = 100L)))
        coEvery { calendarRepo.deleteEntryByUid("a") } just Runs

        val payload = """{"entries":[],"tombstones":[{"uid":"a","deletedAt":9999}]}"""
        handler.merge(payload)

        coVerify(exactly = 1) { calendarRepo.deleteEntryByUid("a") }
    }

    @Test
    fun `merge resolves an unknown layer name by auto-creating it, matching import's convention`() = runTest {
        every { calendarRepo.layers } returns flowOf(emptyList())
        coEvery { calendarRepo.addLayer(any(), any(), any(), any()) } returns 7L
        coEvery { calendarRepo.insertSyncedEntry(any(), any()) } returns 1L

        val payload = """{"entries":[{"uid":"a","type":"EVENT","title":"hi","startMillis":0,"createdAt":100,"updatedAt":100,"layerName":"Travel"}],"tombstones":[]}"""
        handler.merge(payload)

        coVerify(exactly = 1) { calendarRepo.addLayer("Travel", any(), any(), any()) }
        coVerify(exactly = 1) { calendarRepo.insertSyncedEntry(match { it.layerId == 7L }, any()) }
    }

    @Test
    fun `malformed merge payload returns a failure without touching the repository`() = runTest {
        val result = handler.merge("{ not json")

        assertFalse(result.ok)
        coVerify(exactly = 0) { calendarRepo.entriesSnapshot() }
    }

    @Test
    fun `merge while locked returns a failure without touching the repository`() = runTest {
        every { settingsRepo.getSnapshot() } returns CalendarSettings(isBiometricRequired = true)
        every { sessionManager.isSessionValid(any()) } returns false

        val result = handler.merge("""{"entries":[],"tombstones":[]}""")

        assertFalse(result.ok)
        coVerify(exactly = 0) { calendarRepo.entriesSnapshot() }
    }
}
