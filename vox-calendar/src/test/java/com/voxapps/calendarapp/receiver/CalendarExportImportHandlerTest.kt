package com.voxapps.calendarapp.receiver

import android.content.Context
import com.voxapps.attachments.AttachmentDao
import com.voxapps.attachments.AttachmentEntity
import com.voxapps.calendarapp.data.CalendarAttachments
import com.voxapps.calendarapp.data.CalendarEntry
import com.voxapps.calendarapp.data.CalendarEntryType
import com.voxapps.calendarapp.data.CalendarEntryWithTags
import com.voxapps.calendarapp.data.CalendarLayer
import com.voxapps.calendarapp.data.CalendarRepository
import com.voxapps.calendarapp.data.RecurrenceFrequency
import com.voxapps.calendarapp.data.ToDoList
import com.voxapps.calendarapp.data.ToDoListDao
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CalendarExportImportHandlerTest {

    private lateinit var context: Context
    private lateinit var settingsRepo: CalendarSettingsRepository
    private lateinit var sessionManager: SessionManager
    private lateinit var calendarRepo: CalendarRepository
    private lateinit var attachmentDao: AttachmentDao
    private lateinit var toDoListDao: ToDoListDao
    private lateinit var handler: CalendarExportImportHandler

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        settingsRepo = mockk()
        sessionManager = mockk()
        calendarRepo = mockk()
        attachmentDao = mockk(relaxed = true)
        toDoListDao = mockk()
        handler = CalendarExportImportHandler(context, settingsRepo, sessionManager, calendarRepo, attachmentDao, toDoListDao)

        every { settingsRepo.getSnapshot() } returns CalendarSettings(isBiometricRequired = false)
        coEvery { calendarRepo.layersSnapshot() } returns emptyList()
        coEvery { calendarRepo.entriesSnapshot() } returns emptyList()
        coEvery { calendarRepo.deleteEntryById(any()) } just Runs
        coEvery { calendarRepo.getRemindersForEntry(any()) } returns emptyList()
        every { toDoListDao.observeAll() } returns flowOf(emptyList())
    }

    private fun entry(id: Long, layerId: Long = 1L, uid: String = "uid-$id") = CalendarEntryWithTags(
        entry = CalendarEntry(
            id = id, uid = uid, type = CalendarEntryType.EVENT, title = "Entry $id",
            startMillis = 0L, layerId = layerId, createdAt = 0L, updatedAt = 0L
        )
    )

    @Test
    fun `export without includePhotos never touches zip building, attachmentUri stays null`() = runTest {
        coEvery { calendarRepo.entriesSnapshot() } returns listOf(entry(1))

        val result = handler.export(includePhotos = false)

        assertTrue(result.ok)
        assertNull(result.attachmentUri)
    }

    @Test
    fun `export nests each entry's attachments under its own entry`() = runTest {
        coEvery { calendarRepo.entriesSnapshot() } returns listOf(entry(1))
        coEvery { attachmentDao.getFor(CalendarAttachments.RECORD_TYPE, 1L) } returns listOf(
            AttachmentEntity(id = 9, recordType = CalendarAttachments.RECORD_TYPE, recordId = 1L, fileName = "att_1.jpg", source = "manual", createdAt = 55L)
        )

        val result = handler.export(includePhotos = false)

        val eventJson = JSONObject(result.text).getJSONArray("events").getJSONObject(0)
        val attachmentsJson = eventJson.getJSONArray("attachments")
        assertEquals(1, attachmentsJson.length())
        assertEquals("att_1.jpg", attachmentsJson.getJSONObject(0).getString("fileName"))
    }

    @Test
    fun `import only deletes pre-existing entries, replace-by-snapshot semantics`() = runTest {
        coEvery { calendarRepo.layersSnapshot() } returns listOf(
            CalendarLayer(id = 1, name = "Personal", colorArgb = 0, isDefault = true, position = 0, createdAt = 0L)
        )
        coEvery { calendarRepo.entriesSnapshot() } returns listOf(entry(100))
        coEvery {
            calendarRepo.addEntry(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
            )
        } returns 1L

        val payload = """{"layers":[{"id":1,"name":"Personal"}],"events":[{"title":"restored","layerId":1,"startMillis":0}]}"""
        handler.import(payload)

        coVerify(exactly = 1) { calendarRepo.deleteEntryById(100) }
    }

    @Test
    fun `import inserts each nested attachment against the newly created entry's id`() = runTest {
        coEvery { calendarRepo.layersSnapshot() } returns listOf(
            CalendarLayer(id = 1, name = "Personal", colorArgb = 0, isDefault = true, position = 0, createdAt = 0L)
        )
        coEvery {
            calendarRepo.addEntry(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
            )
        } returns 42L

        val payload = """{"layers":[{"id":1,"name":"Personal"}],"events":[{"title":"e","layerId":1,"startMillis":0,
            "attachments":[{"fileName":"att_1.jpg","source":"manual","createdAt":10}]}]}"""
        handler.import(payload)

        coVerify(exactly = 1) {
            attachmentDao.insert(
                AttachmentEntity(recordType = CalendarAttachments.RECORD_TYPE, recordId = 42L, fileName = "att_1.jpg", source = "manual", createdAt = 10L)
            )
        }
    }

    @Test
    fun `import skips attachments with a blank fileName`() = runTest {
        coEvery { calendarRepo.layersSnapshot() } returns listOf(
            CalendarLayer(id = 1, name = "Personal", colorArgb = 0, isDefault = true, position = 0, createdAt = 0L)
        )
        coEvery {
            calendarRepo.addEntry(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
            )
        } returns 3L

        val payload = """{"layers":[{"id":1,"name":"Personal"}],"events":[{"title":"e","layerId":1,"startMillis":0,
            "attachments":[{"fileName":"","source":"manual","createdAt":1}]}]}"""
        handler.import(payload)

        coVerify(exactly = 0) { attachmentDao.insert(any()) }
    }

    @Test
    fun `malformed payload returns a failure result without touching the repository`() = runTest {
        val result = handler.import("{ not json")

        assertFalse(result.ok)
        coVerify(exactly = 0) { calendarRepo.entriesSnapshot() }
    }

    @Test
    fun `export splits entries into events and todoItems by listId, and includes todoLists`() = runTest {
        val plainEvent = entry(1, layerId = 1L)
        val todoItem = CalendarEntryWithTags(
            entry = CalendarEntry(
                id = 2, uid = "uid-2", type = CalendarEntryType.TASK, title = "Buy milk",
                startMillis = null, layerId = 1L, listId = 9L, position = 0, createdAt = 0L, updatedAt = 0L
            )
        )
        coEvery { calendarRepo.entriesSnapshot() } returns listOf(plainEvent, todoItem)
        every { toDoListDao.observeAll() } returns flowOf(
            listOf(ToDoList(id = 9L, uid = "list-uid", title = "Shopping", colorArgb = 0, layerId = 1L, createdAt = 0L, updatedAt = 0L))
        )

        val result = handler.export(includePhotos = false)

        val json = JSONObject(result.text)
        assertEquals(1, json.getJSONArray("events").length())
        assertEquals(1, json.getJSONArray("todoItems").length())
        assertEquals("Buy milk", json.getJSONArray("todoItems").getJSONObject(0).getString("title"))
        assertEquals(1, json.getJSONArray("todoLists").length())
        assertEquals("Shopping", json.getJSONArray("todoLists").getJSONObject(0).getString("title"))
    }

    @Test
    fun `export includes each entry's reminders`() = runTest {
        coEvery { calendarRepo.entriesSnapshot() } returns listOf(entry(1))
        coEvery { calendarRepo.getRemindersForEntry(1L) } returns listOf(
            com.voxapps.calendarapp.data.CalendarReminder(id = 1, entryId = 1L, offsetMinutesBefore = 30)
        )

        val result = handler.export(includePhotos = false)

        val eventJson = JSONObject(result.text).getJSONArray("events").getJSONObject(0)
        val reminders = eventJson.getJSONArray("reminders")
        assertEquals(1, reminders.length())
        assertEquals(30, reminders.getInt(0))
    }

    @Test
    fun `import merges todoLists by title instead of duplicating`() = runTest {
        coEvery { calendarRepo.layersSnapshot() } returns listOf(
            CalendarLayer(id = 1, name = "Personal", colorArgb = 0, isDefault = true, position = 0, createdAt = 0L)
        )
        every { toDoListDao.observeAll() } returns flowOf(
            listOf(ToDoList(id = 5L, uid = "existing-uid", title = "Shopping", colorArgb = 0, layerId = 1L, createdAt = 0L, updatedAt = 0L))
        )

        val payload = """{"layers":[{"id":1,"name":"Personal"}],
            "todoLists":[{"id":1,"title":"Shopping","colorArgb":0,"layerId":1}]}"""
        handler.import(payload)

        io.mockk.coVerify(exactly = 0) { toDoListDao.insert(any()) }
    }

    @Test
    fun `import creates a new todoItem after resolving its list by id mapping`() = runTest {
        coEvery { calendarRepo.layersSnapshot() } returns listOf(
            CalendarLayer(id = 1, name = "Personal", colorArgb = 0, isDefault = true, position = 0, createdAt = 0L)
        )
        every { toDoListDao.observeAll() } returns flowOf(emptyList())
        io.mockk.coEvery { toDoListDao.insert(any()) } returns 77L
        coEvery {
            calendarRepo.addEntry(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
            )
        } returns 42L

        val payload = """{"layers":[{"id":1,"name":"Personal"}],
            "todoLists":[{"id":1,"title":"Groceries","colorArgb":0,"layerId":1}],
            "todoItems":[{"title":"Buy milk","layerId":1,"listId":1}]}"""
        val result = handler.import(payload)

        assertTrue(result.ok)
        assertTrue(result.text.contains("1 to-do items imported"))
        coVerify(exactly = 1) { toDoListDao.insert(any()) }
    }

    @Test
    fun `import skips a todoItem whose list never resolves`() = runTest {
        coEvery { calendarRepo.layersSnapshot() } returns listOf(
            CalendarLayer(id = 1, name = "Personal", colorArgb = 0, isDefault = true, position = 0, createdAt = 0L)
        )
        every { toDoListDao.observeAll() } returns flowOf(emptyList())

        // No "todoLists" key at all — listId 1 can never resolve, so the item must be skipped
        // rather than crash or attach to some arbitrary default list.
        val payload = """{"layers":[{"id":1,"name":"Personal"}],
            "todoItems":[{"title":"Buy milk","layerId":1,"listId":1}]}"""
        val result = handler.import(payload)

        assertTrue(result.ok)
        assertTrue(result.text.contains("0 to-do items imported"))
        coVerify(exactly = 0) { calendarRepo.addEntry(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `settings export then import round-trips fields the old hand-written allowlist used to drop`() = runTest {
        // todayEffectColor2/notificationsVolume were both missing from the old hand-maintained
        // toJson() (only 8 of 25 fields were exported) — this is exactly the class of field the
        // Gson-reflection switch fixes; a regression back to a manual allowlist would silently
        // reset these on the next restore without this test failing loudly first.
        val original = CalendarSettings(
            isBiometricRequired = false,
            todayEffectColor2 = 0xFF00FF00L,
            notificationsVolume = 42,
            onboardingCompleted = true
        )
        every { settingsRepo.getSnapshot() } returns original
        val restored = io.mockk.slot<CalendarSettings>()
        coEvery { settingsRepo.restoreSettings(capture(restored)) } just Runs

        val exportResult = handler.export(includePhotos = false)
        handler.import(exportResult.text)

        assertEquals(original.todayEffectColor2, restored.captured.todayEffectColor2)
        assertEquals(original.notificationsVolume, restored.captured.notificationsVolume)
        // onboardingCompleted is the one deliberate exclusion — it must NOT survive the round-trip.
        assertFalse(restored.captured.onboardingCompleted)
    }
}
