package com.voxapps.calendarapp.data

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ToDoRepository]'s rewritten internals (mirrors [com.voxapps.calendarapp.receiver
 * .CalendarExportImportHandlerTest]'s MockK style) — since the unification (see [ToDoItem]'s doc
 * comment), a to-do item IS a [CalendarEntry] row, so [ToDoRepository] is exercised entirely through
 * mocked [ToDoListDao]/[CalendarEntryDao]/[CalendarRepository], no real database needed.
 */
class ToDoRepositoryTest {

    private lateinit var listDao: ToDoListDao
    private lateinit var entryDao: CalendarEntryDao
    private lateinit var calendarRepository: CalendarRepository
    private lateinit var repository: ToDoRepository

    private val now = 1_000_000L

    private fun list(
        id: Long,
        title: String,
        layerId: Long = 1L,
        colorArgb: Long = 111L,
        routineDaysMask: Int = 0,
        routineLastResetDay: Long = 0
    ) = ToDoList(
        id = id, uid = "list-uid-$id", title = title, colorArgb = colorArgb, layerId = layerId,
        createdAt = now, updatedAt = now,
        routineDaysMask = routineDaysMask, routineLastResetDay = routineLastResetDay
    )

    private fun entry(
        id: Long,
        listId: Long?,
        title: String = "Item $id",
        position: Int = 0,
        colorArgb: Long? = 100L + id,
        startMillis: Long? = null,
        completed: Boolean = false,
        isImportant: Boolean = false,
        comments: String? = null
    ) = CalendarEntry(
        id = id, uid = "uid-$id", type = CalendarEntryType.TASK, title = title,
        startMillis = startMillis, allDay = false, completed = completed, layerId = 1L,
        isImportant = isImportant, comments = comments, listId = listId, position = position,
        colorArgb = colorArgb, createdAt = now, updatedAt = now
    )

    @Before
    fun setup() {
        listDao = mockk()
        entryDao = mockk()
        calendarRepository = mockk()
        // ToDoRepository.lists is a property initializer — it captures whatever Flow
        // listDao.observeAll() returns AT CONSTRUCTION TIME. Re-stubbing the mock afterward has no
        // effect on an already-built repository, so buildRepository() must be called (with the
        // desired list contents already stubbed) before exercising anything that reads `lists`.
        repository = buildRepository()
    }

    private fun buildRepository(lists: List<ToDoList> = emptyList()): ToDoRepository {
        every { listDao.observeAll() } returns flowOf(lists)
        every { entryDao.observeAllListItems() } returns flowOf(emptyList())
        coEvery { listDao.getAll() } returns lists
        return ToDoRepository(listDao, entryDao, calendarRepository)
    }

    @Test
    fun `addItem appends at the end using the highest existing position plus one`() = runTest {
        val listId = 1L
        coEvery { listDao.getById(listId) } returns list(listId, "Groceries")
        coEvery { entryDao.getForList(listId) } returns listOf(
            entry(1, listId, position = 0),
            entry(2, listId, position = 2) // a gap — max position is 2, not size (2)
        )
        val inserted = slot<CalendarEntry>()
        coEvery { entryDao.insert(capture(inserted)) } returns 99L

        val result = repository.addItem(listId, "  Buy milk  ")

        assertEquals(99L, result)
        assertEquals("Buy milk", inserted.captured.title) // trimmed
        assertEquals(3, inserted.captured.position) // maxOf(0,2) + 1
        assertEquals(listId, inserted.captured.listId)
        assertNull(inserted.captured.startMillis)
        assertEquals(CalendarEntryType.TASK, inserted.captured.type)
        coVerify(exactly = 0) { entryDao.update(any()) } // no existing item needed to shift
    }

    @Test
    fun `addItem inserted mid-list shifts every item at or after that position down one`() = runTest {
        val listId = 1L
        coEvery { listDao.getById(listId) } returns list(listId, "Groceries")
        coEvery { entryDao.getForList(listId) } returns listOf(
            entry(1, listId, position = 0),
            entry(2, listId, position = 1)
        )
        coEvery { entryDao.update(any()) } just Runs
        coEvery { entryDao.insert(any()) } returns 50L

        repository.addItem(listId, "New item", atPosition = 1)

        coVerify(exactly = 1) { entryDao.update(match { it.id == 2L && it.position == 2 }) }
        coVerify(exactly = 0) { entryDao.update(match { it.id == 1L }) } // untouched, before the insertion point
    }

    @Test
    fun `updateItemImportant writes directly via entryDao, never touching reminders`() = runTest {
        val existing = entry(1, listId = 1L, isImportant = false)
        coEvery { entryDao.getById(1L) } returns existing
        coEvery { entryDao.update(any()) } just Runs

        repository.updateItemImportant(existing.toToDoItem(), true)

        coVerify(exactly = 1) { entryDao.update(match { it.id == 1L && it.isImportant }) }
        coVerify(exactly = 0) { calendarRepository.updateEntry(any(), any(), any()) }
    }

    @Test
    fun `toggleDone flips completed directly, never touching reminders`() = runTest {
        val existing = entry(1, listId = 1L, completed = false)
        coEvery { entryDao.getById(1L) } returns existing
        coEvery { entryDao.update(any()) } just Runs

        repository.toggleDone(existing.toToDoItem())

        coVerify(exactly = 1) { entryDao.update(match { it.id == 1L && it.completed }) }
        coVerify(exactly = 0) { calendarRepository.updateEntry(any(), any(), any()) }
    }

    @Test
    fun `setItemDueDate preserves the item's current reminder offsets instead of wiping them`() = runTest {
        val listId = 1L
        val list = list(listId, "Groceries")
        val existing = entry(1, listId, startMillis = null)
        coEvery { entryDao.getById(1L) } returns existing
        coEvery { calendarRepository.getRemindersForEntry(1L) } returns listOf(
            CalendarReminder(id = 10, entryId = 1L, offsetMinutesBefore = 10),
            CalendarReminder(id = 11, entryId = 1L, offsetMinutesBefore = 30)
        )
        coEvery { calendarRepository.updateEntry(any(), any(), any()) } just Runs

        repository.setItemDueDate(existing.toToDoItem(), 5_000L, list)

        coVerify(exactly = 1) {
            calendarRepository.updateEntry(
                match { it.id == 1L && it.startMillis == 5_000L },
                emptyList(),
                listOf(10, 30)
            )
        }
    }

    @Test
    fun `deleteItem deletes via calendarRepository and renormalizes remaining positions`() = runTest {
        val listId = 1L
        val existing = entry(2, listId, position = 1)
        coEvery { entryDao.getById(2L) } returns existing
        coEvery { calendarRepository.deleteEntry(existing) } just Runs
        // After the delete, item id=3 is the only one left, still carrying its old position (2) —
        // must be renormalized to 0 since it's now the sole/first item.
        coEvery { entryDao.getForList(listId) } returns listOf(entry(3, listId, position = 2))
        coEvery { entryDao.update(any()) } just Runs

        repository.deleteItem(existing.toToDoItem())

        coVerify(exactly = 1) { calendarRepository.deleteEntry(existing) }
        coVerify(exactly = 1) { entryDao.update(match { it.id == 3L && it.position == 0 }) }
    }

    @Test
    fun `addParsedItem resolves an exact list name match`() = runTest {
        val groceries = list(1L, "Groceries")
        val work = list(2L, "Work")
        repository = buildRepository(listOf(groceries, work))
        coEvery { listDao.getById(1L) } returns groceries
        coEvery { entryDao.getForList(1L) } returns emptyList()
        coEvery { entryDao.insert(any()) } returns 77L

        val itemId = repository.addParsedItem("Groceries", "Buy bread", dueMillis = null, defaultLayerId = null)

        assertEquals(77L, itemId)
        coVerify(exactly = 1) { entryDao.insert(match { it.listId == 1L }) }
    }

    @Test
    fun `addParsedItem falls back to the most-recently-created list when no name is given`() = runTest {
        // ToDoListDao.observeAll is ORDER BY createdAt DESC in production — mocked here already in
        // that order, so lists.first() is the most-recently-created one.
        val mostRecent = list(5L, "Errands")
        val older = list(9L, "Old list")
        repository = buildRepository(listOf(mostRecent, older))
        coEvery { listDao.getById(5L) } returns mostRecent
        coEvery { entryDao.getForList(5L) } returns emptyList()
        coEvery { entryDao.insert(any()) } returns 1L

        repository.addParsedItem(spokenListName = null, text = "Call the dentist", dueMillis = null, defaultLayerId = null)

        coVerify(exactly = 1) { entryDao.insert(match { it.listId == 5L }) }
    }

    @Test
    fun `addParsedItem bootstraps a single list when none exist yet, using the spoken name`() = runTest {
        every { listDao.observeAll() } returns flowOf(emptyList())
        coEvery { listDao.getAll() } returns emptyList()
        coEvery { calendarRepository.layersSnapshot() } returns listOf(
            CalendarLayer(id = 1L, name = "Personal", colorArgb = 0L, isDefault = true, position = 0, createdAt = now)
        )
        val newList = list(1L, "Groceries")
        coEvery { listDao.insert(any()) } returns 1L
        coEvery { listDao.getById(1L) } returns newList
        coEvery { entryDao.getForList(1L) } returns emptyList()
        coEvery { entryDao.insert(any()) } returns 1L

        repository.addParsedItem("Groceries", "Buy bread", dueMillis = null, defaultLayerId = null)

        coVerify(exactly = 1) { listDao.insert(match { it.title == "Groceries" && it.layerId == 1L }) }
    }

    @Test
    fun `addParsedItem bootstraps a list titled Tasks when no name was spoken either`() = runTest {
        every { listDao.observeAll() } returns flowOf(emptyList())
        coEvery { listDao.getAll() } returns emptyList()
        coEvery { calendarRepository.layersSnapshot() } returns listOf(
            CalendarLayer(id = 2L, name = "Personal", colorArgb = 0L, isDefault = true, position = 0, createdAt = now)
        )
        val newList = list(1L, "Tasks", layerId = 2L)
        coEvery { listDao.insert(any()) } returns 1L
        coEvery { listDao.getById(1L) } returns newList
        coEvery { entryDao.getForList(1L) } returns emptyList()
        coEvery { entryDao.insert(any()) } returns 1L

        repository.addParsedItem(spokenListName = null, text = "Call the dentist", dueMillis = null, defaultLayerId = null)

        coVerify(exactly = 1) { listDao.insert(match { it.title == "Tasks" }) }
    }

    @Test
    fun `addParsedItem with a due date also attaches it via setItemDueDate`() = runTest {
        val groceries = list(1L, "Groceries")
        repository = buildRepository(listOf(groceries))
        coEvery { listDao.getById(1L) } returns groceries
        coEvery { entryDao.getForList(1L) } returns emptyList() andThen listOf(entry(42L, 1L))
        coEvery { entryDao.insert(any()) } returns 42L
        coEvery { entryDao.getById(42L) } returns entry(42L, 1L)
        coEvery { calendarRepository.getRemindersForEntry(42L) } returns emptyList()
        coEvery { calendarRepository.updateEntry(any(), any(), any()) } just Runs

        repository.addParsedItem("Groceries", "Buy bread", dueMillis = 12_345L, defaultLayerId = null)

        coVerify(exactly = 1) { calendarRepository.updateEntry(match { it.id == 42L && it.startMillis == 12_345L }, emptyList(), emptyList()) }
    }

    // --- routine-list midnight reset (ToDoList.routineDaysMask) ---

    @Test
    fun `resetRoutinesForToday clears undated done items and stamps the day for an active routine`() = runTest {
        // 2026-03-02 is a Monday; the list's mask contains Monday.
        val today = java.time.LocalDate.of(2026, 3, 2)
        val routine = list(1L, "Morning", routineDaysMask = WeekdayMask.bit(java.time.DayOfWeek.MONDAY))
        repository = buildRepository(lists = listOf(routine))
        coEvery { entryDao.clearCompletedUndatedForList(1L, any()) } just Runs
        coEvery { listDao.update(any()) } just Runs

        val didReset = repository.resetRoutinesForToday(today)

        assertEquals(true, didReset)
        coVerify(exactly = 1) { entryDao.clearCompletedUndatedForList(1L, any()) }
        coVerify(exactly = 1) {
            listDao.update(match { it.id == 1L && it.routineLastResetDay == today.toEpochDay() })
        }
    }

    @Test
    fun `resetRoutinesForToday skips a routine whose mask excludes today`() = runTest {
        // Monday reset day, Tuesday-only routine.
        val today = java.time.LocalDate.of(2026, 3, 2)
        val routine = list(1L, "Tuesdays", routineDaysMask = WeekdayMask.bit(java.time.DayOfWeek.TUESDAY))
        repository = buildRepository(lists = listOf(routine))

        val didReset = repository.resetRoutinesForToday(today)

        assertEquals(false, didReset)
        coVerify(exactly = 0) { entryDao.clearCompletedUndatedForList(any(), any()) }
        coVerify(exactly = 0) { listDao.update(any()) }
    }

    @Test
    fun `resetRoutinesForToday is idempotent per day via routineLastResetDay`() = runTest {
        val today = java.time.LocalDate.of(2026, 3, 2)
        val alreadyReset = list(
            1L, "Morning",
            routineDaysMask = WeekdayMask.ALL,
            routineLastResetDay = today.toEpochDay()
        )
        repository = buildRepository(lists = listOf(alreadyReset))

        val didReset = repository.resetRoutinesForToday(today)

        assertEquals(false, didReset)
        coVerify(exactly = 0) { entryDao.clearCompletedUndatedForList(any(), any()) }
    }

    @Test
    fun `resetRoutinesForToday never touches a non-routine list`() = runTest {
        val today = java.time.LocalDate.of(2026, 3, 2)
        repository = buildRepository(lists = listOf(list(1L, "Groceries")))

        val didReset = repository.resetRoutinesForToday(today)

        assertEquals(false, didReset)
        coVerify(exactly = 0) { entryDao.clearCompletedUndatedForList(any(), any()) }
    }

    @Test
    fun `updateListRoutineDays clamps the mask to the seven weekday bits`() = runTest {
        val target = list(1L, "Morning")
        repository = buildRepository(lists = listOf(target))
        coEvery { listDao.update(any()) } just Runs

        repository.updateListRoutineDays(target, 0xFFFF)

        coVerify { listDao.update(match { it.routineDaysMask == WeekdayMask.ALL }) }
    }

    @Test
    fun `switching a routine on mid-day stamps today so the first reset is tomorrow`() = runTest {
        val today = java.time.LocalDate.of(2026, 3, 2)
        val target = list(1L, "Morning")
        repository = buildRepository(lists = listOf(target))
        coEvery { listDao.update(any()) } just Runs

        repository.updateListRoutineDays(target, WeekdayMask.ALL, today)

        coVerify { listDao.update(match { it.routineLastResetDay == today.toEpochDay() }) }
    }

    @Test
    fun `re-picking days on an already-on routine keeps its reset stamp`() = runTest {
        val today = java.time.LocalDate.of(2026, 3, 2)
        val target = list(1L, "Morning", routineDaysMask = WeekdayMask.ALL, routineLastResetDay = 500L)
        repository = buildRepository(lists = listOf(target))
        coEvery { listDao.update(any()) } just Runs

        repository.updateListRoutineDays(target, WeekdayMask.bit(java.time.DayOfWeek.MONDAY), today)

        coVerify { listDao.update(match { it.routineLastResetDay == 500L }) }
    }
}
