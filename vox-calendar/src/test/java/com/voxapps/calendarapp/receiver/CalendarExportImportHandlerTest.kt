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
import com.voxapps.calendarapp.data.preferences.CalendarSettings
import com.voxapps.calendarapp.data.preferences.CalendarSettingsRepository
import com.voxapps.calendarapp.state.SessionManager
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
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
    private lateinit var handler: CalendarExportImportHandler

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        settingsRepo = mockk()
        sessionManager = mockk()
        calendarRepo = mockk()
        attachmentDao = mockk(relaxed = true)
        handler = CalendarExportImportHandler(context, settingsRepo, sessionManager, calendarRepo, attachmentDao)

        every { settingsRepo.getSnapshot() } returns CalendarSettings(isBiometricRequired = false)
        coEvery { calendarRepo.layersSnapshot() } returns emptyList()
        coEvery { calendarRepo.entriesSnapshot() } returns emptyList()
        coEvery { calendarRepo.deleteEntryById(any()) } just Runs
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
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
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
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
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
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
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
}
