package com.voxapps.calendarapp.domain.subscription

import com.voxapps.calendarapp.data.CalendarEntry
import com.voxapps.calendarapp.data.CalendarEntryType
import com.voxapps.calendarapp.data.CalendarLayer
import com.voxapps.calendarapp.data.CalendarLayerKind
import com.voxapps.calendarapp.data.CalendarRepository
import com.voxapps.calendarapp.data.RecurrenceFrequency
import com.voxapps.calendarapp.domain.ics.IcsExportImportUtil
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class CalendarSubscriptionSyncEngineTest {

    private val now = 1_000_000L
    private val subscribedLayer = CalendarLayer(
        id = 5, name = "Holidays", colorArgb = 0, createdAt = now,
        kind = CalendarLayerKind.SUBSCRIBED, subscriptionUrl = "https://example.com/cal.ics"
    )

    private fun ics(vararg events: Pair<String, String>): InputStream {
        val body = events.joinToString("\n") { (uid, summary) ->
            "BEGIN:VEVENT\nUID:$uid\nSUMMARY:$summary\nDTSTART:20260101T000000Z\nDTEND:20260102T000000Z\nEND:VEVENT"
        }
        return ByteArrayInputStream(
            "BEGIN:VCALENDAR\nVERSION:2.0\nPRODID:-//Test//Test//EN\n$body\nEND:VCALENDAR".toByteArray()
        )
    }

    /** Builds a local row that already exactly matches what parsing [ics] for ([uid], [title]) would
     *  produce (real start/end millis, allDay, etc.) — needed so the "unchanged" test's equality
     *  check is genuinely exercised rather than trivially failing on placeholder date mismatches. */
    private fun entry(id: Long, uid: String, title: String, individualOffsets: String? = null): CalendarEntry {
        val parsed = IcsExportImportUtil.read(ics(uid to title)).single()
        return CalendarEntry(
            id = id, uid = uid, type = parsed.type, title = title,
            startMillis = parsed.startMillis, endMillis = parsed.endMillis, allDay = parsed.allDay,
            recurrenceFrequency = parsed.recurrenceFrequency, recurrenceInterval = parsed.recurrenceInterval,
            recurrenceUntilMillis = parsed.recurrenceUntilMillis, layerId = 5L,
            createdAt = now, updatedAt = now, individualReminderOffsetsMinutes = individualOffsets
        )
    }

    @Test
    fun `first sync of an empty calendar inserts every parsed entry`() = runTest {
        val repository = mockk<CalendarRepository>(relaxed = true)
        coEvery { repository.uidsForLayer(5L) } returns emptyList()
        coEvery { repository.getIdByUid(any()) } returns null

        CalendarSubscriptionSyncEngine.sync(repository, subscribedLayer) { ics("uid-1" to "New Year", "uid-2" to "Independence Day") }

        // Every default-valued param must get an explicit `any()` here — omitting one makes Kotlin
        // compile in its literal default instead (e.g. `now`'s default is System.currentTimeMillis(),
        // evaluated fresh at verify time, which will never equal what was actually passed at call time).
        fun verifyInserted(uid: String) {
            coVerify(exactly = 1) {
                repository.addEntry(
                    uid = uid, type = any(), title = any(), description = any(), location = any(),
                    startMillis = any(), endMillis = any(), allDay = any(), completed = any(), isImportant = any(),
                    recurrenceFrequency = any(), recurrenceInterval = any(), recurrenceUntilMillis = any(),
                    layerId = 5L, tags = any(), reminderOffsetsMinutes = any(), now = any(),
                    listId = any(), position = any(), colorArgb = any(), comments = any()
                )
            }
        }
        verifyInserted("uid-1")
        verifyInserted("uid-2")
        coVerify(exactly = 1) { repository.setSyncStatus(5L, lastSyncedAt = any(), lastSyncError = null) }
    }

    @Test
    fun `a uid removed upstream is deleted via deleteEntryByUid, not a bulk wipe`() = runTest {
        val repository = mockk<CalendarRepository>(relaxed = true)
        coEvery { repository.uidsForLayer(5L) } returns listOf("uid-stays", "uid-removed")
        coEvery { repository.getIdByUid("uid-stays") } returns 1L
        coEvery { repository.getEntryById(1L) } returns entry(1L, "uid-stays", "Stays")

        CalendarSubscriptionSyncEngine.sync(repository, subscribedLayer) { ics("uid-stays" to "Stays") }

        coVerify(exactly = 1) { repository.deleteEntryByUid("uid-removed") }
        coVerify(exactly = 0) { repository.deleteEntryByUid("uid-stays") }
    }

    @Test
    fun `a changed entry updates in place preserving its individual reminder offsets`() = runTest {
        val repository = mockk<CalendarRepository>(relaxed = true)
        coEvery { repository.uidsForLayer(5L) } returns listOf("uid-1")
        coEvery { repository.getIdByUid("uid-1") } returns 1L
        coEvery { repository.getEntryById(1L) } returns entry(1L, "uid-1", "Old Title", individualOffsets = "30,1440")

        CalendarSubscriptionSyncEngine.sync(repository, subscribedLayer) { ics("uid-1" to "New Title") }

        coVerify(exactly = 1) {
            repository.updateEntry(
                match { it.id == 1L && it.title == "New Title" },
                any(),
                match { it.toSet() == setOf(30, 1440) }
            )
        }
    }

    @Test
    fun `an unchanged entry produces zero updateEntry calls`() = runTest {
        val repository = mockk<CalendarRepository>(relaxed = true)
        coEvery { repository.uidsForLayer(5L) } returns listOf("uid-1")
        coEvery { repository.getIdByUid("uid-1") } returns 1L
        coEvery { repository.getEntryById(1L) } returns entry(1L, "uid-1", "New Year")

        CalendarSubscriptionSyncEngine.sync(repository, subscribedLayer) { ics("uid-1" to "New Year") }

        coVerify(exactly = 0) { repository.updateEntry(any(), any(), any()) }
    }

    @Test
    fun `a fetch failure sets lastSyncError and preserves the previous lastSyncedAt`() = runTest {
        val repository = mockk<CalendarRepository>(relaxed = true)
        coEvery { repository.uidsForLayer(5L) } returns emptyList()

        CalendarSubscriptionSyncEngine.sync(repository, subscribedLayer) { throw RuntimeException("HTTP 404") }

        coVerify(exactly = 1) { repository.setSyncStatus(5L, lastSyncedAt = null, lastSyncError = "HTTP 404", keepLastSyncedAt = true) }
        coVerify(exactly = 0) { repository.setSyncStatus(5L, lastSyncedAt = any(), lastSyncError = null) }
    }
}
