package com.voxapps.calendarapp.data

import android.content.Context
import com.voxapps.attachments.AttachmentDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.verify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [CalendarRepository]'s multi-calendar behavior — mirrors [ToDoRepositoryTest]'s
 * mocked-DAO style (no real database needed).
 */
class CalendarRepositoryTest {

    private lateinit var entryDao: CalendarEntryDao
    private lateinit var layerDao: CalendarLayerDao
    private lateinit var tagDao: CalendarEntryTagDao
    private lateinit var attachmentDao: AttachmentDao
    private lateinit var reminderDao: CalendarReminderDao
    private lateinit var toDoListDao: ToDoListDao
    private lateinit var effects: CalendarPlatformEffects
    private lateinit var repository: CalendarRepository

    private val now = 1_000_000L
    private val mainLayer = CalendarLayer(id = 1, name = "Personal", colorArgb = 111L, isDefault = true, createdAt = now)
    private val otherLayer = CalendarLayer(id = 2, name = "Work", colorArgb = 222L, isDefault = false, createdAt = now)

    @Before
    fun setup() {
        entryDao = mockk()
        layerDao = mockk()
        tagDao = mockk()
        effects = mockk(relaxed = true)
        attachmentDao = mockk()
        reminderDao = mockk()
        toDoListDao = mockk()
        // CalendarRepository's `entriesWithTags`/`layers` properties capture these Flows at
        // construction time (same gotcha as ToDoRepositoryTest's buildRepository doc comment).
        every { entryDao.observeEntriesWithTags() } returns flowOf(emptyList())
        coEvery { entryDao.getEntriesWithTags() } returns emptyList()
        every { layerDao.observeAll() } returns flowOf(listOf(mainLayer, otherLayer))
        coEvery { layerDao.getAll() } returns listOf(mainLayer, otherLayer)
        coEvery { layerDao.getById(any()) } answers { (listOf(mainLayer, otherLayer)).firstOrNull { it.id == firstArg<Long>() } }
        every { tagDao.observeDistinctTagNames() } returns flowOf(emptyList())
        repository = CalendarRepository(
            entryDao, layerDao, tagDao, attachmentDao, reminderDao, toDoListDao,
            mockk<Context>(relaxed = true), effects
        )

        coEvery { attachmentDao.deleteAllFor(any(), any()) } returns emptyList()
        coEvery { reminderDao.deleteForEntry(any()) } returns emptyList()
    }

    @Test
    fun `deleteLayer REASSIGN_TO_MAIN moves entries and to-do lists to the main layer, never deletes rows`() = runTest {
        coEvery { entryDao.reassignLayer(2L, 1L) } returns Unit
        coEvery { toDoListDao.reassignLayer(2L, 1L) } returns Unit
        coEvery { layerDao.delete(otherLayer) } returns Unit

        repository.deleteLayer(otherLayer, CalendarRepository.LayerDeleteMode.REASSIGN_TO_MAIN)

        coVerify(exactly = 1) { entryDao.reassignLayer(2L, 1L) }
        coVerify(exactly = 1) { toDoListDao.reassignLayer(2L, 1L) }
        coVerify(exactly = 1) { layerDao.delete(otherLayer) }
        coVerify(exactly = 0) { entryDao.deleteById(any()) }
        coVerify(exactly = 0) { toDoListDao.delete(any()) }
    }

    @Test
    fun `deleteLayer DELETE_ALL_ENTRIES removes every entry (tombstoned) and every to-do list under it`() = runTest {
        coEvery { entryDao.getIdsForLayer(2L) } returns listOf(10L, 11L)
        coEvery { entryDao.getUidById(10L) } returns "uid-10"
        coEvery { entryDao.getUidById(11L) } returns "uid-11"
        coEvery { entryDao.deleteById(10L) } returns Unit
        coEvery { entryDao.deleteById(11L) } returns Unit
        coEvery { entryDao.insertTombstone(any()) } returns Unit
        val staleList = ToDoList(id = 5, uid = "list-uid", title = "Sprint tasks", colorArgb = 333L, layerId = 2L, createdAt = now, updatedAt = now)
        coEvery { toDoListDao.getAllForLayer(2L) } returns listOf(staleList)
        coEvery { toDoListDao.delete(staleList) } returns Unit
        coEvery { layerDao.delete(otherLayer) } returns Unit

        repository.deleteLayer(otherLayer, CalendarRepository.LayerDeleteMode.DELETE_ALL_ENTRIES)

        coVerify(exactly = 1) { entryDao.deleteById(10L) }
        coVerify(exactly = 1) { entryDao.deleteById(11L) }
        coVerify(exactly = 1) { entryDao.insertTombstone(match { it.uid == "uid-10" }) }
        coVerify(exactly = 1) { entryDao.insertTombstone(match { it.uid == "uid-11" }) }
        coVerify(exactly = 1) { toDoListDao.delete(staleList) }
        coVerify(exactly = 1) { layerDao.delete(otherLayer) }
        coVerify(exactly = 0) { entryDao.reassignLayer(any(), any()) }
        coVerify(exactly = 0) { toDoListDao.reassignLayer(any(), any()) }
    }

    // startMillis is now just a value, not a workaround: the repository fires alarms through the
    // injected CalendarPlatformEffects (see setup()), so nothing here reaches AlarmManager and a
    // future entry would work equally well.
    private fun pastEntry(id: Long, layerId: Long, individualOffsets: String?) = CalendarEntry(
        id = id, uid = "uid-$id", type = CalendarEntryType.EVENT, title = "Entry $id",
        startMillis = 1000L, allDay = false, layerId = layerId, createdAt = now, updatedAt = now,
        individualReminderOffsetsMinutes = individualOffsets
    )

    @Test
    fun `addEntry stores the passed offsets as individual preference and schedules them when the calendar has no override`() = runTest {
        val inserted = slot<CalendarEntry>()
        coEvery { entryDao.insert(capture(inserted)) } returns 100L
        coEvery { tagDao.insertAll(any()) } returns Unit
        coEvery { entryDao.getById(100L) } returns pastEntry(100L, 2L, "30")
        coEvery { reminderDao.insert(any()) } returns 500L

        repository.addEntry(
            type = CalendarEntryType.EVENT, title = "Dentist", description = null, location = null,
            startMillis = 1000L, endMillis = null, allDay = false, layerId = 2L,
            reminderOffsetsMinutes = listOf(30)
        )

        assertEquals("30", inserted.captured.individualReminderOffsetsMinutes)
        coVerify(exactly = 1) { reminderDao.insert(match { it.entryId == 100L && it.offsetMinutesBefore == 30 }) }
        // The alarm itself, not just its DB row — previously unassertable, because the only way to
        // keep ReminderScheduler away from a real AlarmManager was to let it bail out before doing
        // anything worth asserting on.
        verify(exactly = 1) { effects.scheduleReminder(match { it.id == 500L && it.offsetMinutesBefore == 30 }, any()) }
    }

    @Test
    fun `setLayerReminderOffsets ON overrides an entry's individual offsets without erasing them`() = runTest {
        coEvery { layerDao.update(any()) } returns Unit
        // A real Room DAO's observeAll() would reflect the just-applied update on the very next read;
        // this mock has to be told that explicitly since it doesn't track state between stubbed calls.
        every { layerDao.observeAll() } returns flowOf(listOf(mainLayer, otherLayer.copy(reminderOffsetsMinutes = "60,1440")))
        coEvery { layerDao.getAll() } returns listOf(mainLayer, otherLayer.copy(reminderOffsetsMinutes = "60,1440"))
        coEvery { layerDao.getById(any()) } answers { (listOf(mainLayer, otherLayer.copy(reminderOffsetsMinutes = "60,1440"))).firstOrNull { it.id == firstArg<Long>() } }
        coEvery { entryDao.getIdsForLayer(2L) } returns listOf(100L)
        coEvery { entryDao.getById(100L) } returns pastEntry(100L, 2L, "30")
        coEvery { reminderDao.insert(any()) } returns 500L

        repository.setLayerReminderOffsets(2L, listOf(60, 1440))

        coVerify(exactly = 1) { layerDao.update(match { it.id == 2L && it.reminderOffsetsMinutes == "60,1440" }) }
        // The calendar's offsets (60, 1440) are what get scheduled, NOT the entry's own "30" —
        // and the entry row itself is never rewritten (individualReminderOffsetsMinutes untouched).
        coVerify(exactly = 1) { reminderDao.insert(match { it.entryId == 100L && it.offsetMinutesBefore == 60 }) }
        coVerify(exactly = 1) { reminderDao.insert(match { it.entryId == 100L && it.offsetMinutesBefore == 1440 }) }
        coVerify(exactly = 0) { reminderDao.insert(match { it.offsetMinutesBefore == 30 }) }
        coVerify(exactly = 0) { entryDao.update(any()) }
    }

    @Test
    fun `setLayerReminderOffsets OFF restores each entry's own individual offsets`() = runTest {
        coEvery { layerDao.update(any()) } returns Unit
        // Simulates the calendar previously having reminders ON (60,1440) and this call turning it OFF.
        every { layerDao.observeAll() } returns flowOf(listOf(mainLayer, otherLayer.copy(reminderOffsetsMinutes = "")))
        coEvery { layerDao.getAll() } returns listOf(mainLayer, otherLayer.copy(reminderOffsetsMinutes = ""))
        coEvery { layerDao.getById(any()) } answers { (listOf(mainLayer, otherLayer.copy(reminderOffsetsMinutes = ""))).firstOrNull { it.id == firstArg<Long>() } }
        coEvery { entryDao.getIdsForLayer(2L) } returns listOf(100L)
        coEvery { entryDao.getById(100L) } returns pastEntry(100L, 2L, "30")
        coEvery { reminderDao.insert(any()) } returns 500L

        repository.setLayerReminderOffsets(2L, emptyList())

        coVerify(exactly = 1) { layerDao.update(match { it.id == 2L && it.reminderOffsetsMinutes == "" }) }
        coVerify(exactly = 1) { reminderDao.insert(match { it.entryId == 100L && it.offsetMinutesBefore == 30 }) }
    }

    @Test
    fun `bulkDeleteEntries tombstones each id via the normal per-entry delete path, not a raw bulk delete`() = runTest {
        coEvery { entryDao.getUidById(10L) } returns "uid-10"
        coEvery { entryDao.getUidById(11L) } returns "uid-11"
        coEvery { entryDao.deleteById(10L) } returns Unit
        coEvery { entryDao.deleteById(11L) } returns Unit
        coEvery { entryDao.insertTombstone(any()) } returns Unit

        repository.bulkDeleteEntries(listOf(10L, 11L))

        coVerify(exactly = 1) { entryDao.deleteById(10L) }
        coVerify(exactly = 1) { entryDao.deleteById(11L) }
        coVerify(exactly = 1) { entryDao.insertTombstone(match { it.uid == "uid-10" }) }
        coVerify(exactly = 1) { entryDao.insertTombstone(match { it.uid == "uid-11" }) }
    }

    @Test
    fun `bulkMoveEntries reassigns in one query and reschedules each entry against its new calendar`() = runTest {
        coEvery { entryDao.bulkReassignLayer(listOf(100L, 101L), 2L, any()) } returns Unit
        coEvery { entryDao.getById(100L) } returns pastEntry(100L, 2L, "30")
        coEvery { entryDao.getById(101L) } returns pastEntry(101L, 2L, null)
        coEvery { reminderDao.insert(any()) } returns 500L

        repository.bulkMoveEntries(listOf(100L, 101L), newLayerId = 2L)

        coVerify(exactly = 1) { entryDao.bulkReassignLayer(listOf(100L, 101L), 2L, any()) }
        // Layer 2 (otherLayer) has no calendar-level override in this test's setup, so each moved
        // entry's own individual offsets (or lack thereof) are what get rescheduled.
        coVerify(exactly = 1) { reminderDao.insert(match { it.entryId == 100L && it.offsetMinutesBefore == 30 }) }
        coVerify(exactly = 0) { reminderDao.insert(match { it.entryId == 101L }) }
    }

    @Test
    fun `bulkMoveEntries does nothing for an empty id list`() = runTest {
        repository.bulkMoveEntries(emptyList(), newLayerId = 2L)
        coVerify(exactly = 0) { entryDao.bulkReassignLayer(any(), any(), any()) }
    }

    @Test
    fun `setMainLayer promotes the target and demotes the previous main in the same pass`() = runTest {
        coEvery { layerDao.update(any()) } returns Unit

        repository.setMainLayer(2L)

        coVerify(exactly = 1) { layerDao.update(match { it.id == 1L && !it.isDefault }) }
        coVerify(exactly = 1) { layerDao.update(match { it.id == 2L && it.isDefault }) }
    }

    @Test
    fun `setMainLayer refuses a subscribed calendar, leaving the current main untouched`() = runTest {
        val subscribed = CalendarLayer(
            id = 3, name = "Holidays", colorArgb = 333L, createdAt = now,
            kind = CalendarLayerKind.SUBSCRIBED, subscriptionUrl = "https://example.com/cal.ics"
        )
        every { layerDao.observeAll() } returns flowOf(listOf(mainLayer, otherLayer, subscribed))
        coEvery { layerDao.getAll() } returns listOf(mainLayer, otherLayer, subscribed)
        coEvery { layerDao.getById(any()) } answers { (listOf(mainLayer, otherLayer, subscribed)).firstOrNull { it.id == firstArg<Long>() } }

        repository.setMainLayer(3L)

        coVerify(exactly = 0) { layerDao.update(any()) }
    }

    @Test
    fun `setMainLayer is a no-op when the target is already main`() = runTest {
        repository.setMainLayer(1L)
        coVerify(exactly = 0) { layerDao.update(any()) }
    }

    @Test
    fun `reorderLayers rewrites position to each id's new index, skipping unchanged rows`() = runTest {
        coEvery { layerDao.update(any()) } returns Unit
        // mainLayer/otherLayer both default to position 0, so moving otherLayer to index 0 leaves it
        // unchanged (no write), while mainLayer moves to index 1 (one write).
        repository.reorderLayers(listOf(2L, 1L))

        coVerify(exactly = 1) { layerDao.update(match { it.id == 1L && it.position == 1 }) }
        coVerify(exactly = 0) { layerDao.update(match { it.id == 2L }) }
    }
}
