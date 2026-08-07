package com.voxapps.calendarapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * Replays the exact SQL of [CalendarDatabase]'s 10->11 migration against a real, fresh in-memory
 * SQLite connection (same "no Room MigrationTestHelper infra" reasoning as [Migration9to10SqlTest]) —
 * verifies the new calendar_layers/calendar_entries columns land with correct defaults, and that the
 * individualReminderOffsetsMinutes backfill from the existing `reminders` table works.
 */
class Migration10to11SqlTest {

    private val statements = listOf(
        "ALTER TABLE calendar_layers ADD COLUMN kind TEXT NOT NULL DEFAULT 'LOCAL'",
        "ALTER TABLE calendar_layers ADD COLUMN subscriptionUrl TEXT",
        "ALTER TABLE calendar_layers ADD COLUMN lastSyncedAt INTEGER",
        "ALTER TABLE calendar_layers ADD COLUMN lastSyncError TEXT",
        "ALTER TABLE calendar_layers ADD COLUMN reminderOffsetsMinutes TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE calendar_entries ADD COLUMN individualReminderOffsetsMinutes TEXT",
        "UPDATE calendar_entries SET individualReminderOffsetsMinutes = (" +
            "SELECT GROUP_CONCAT(offsetMinutesBefore) FROM reminders WHERE reminders.entryId = calendar_entries.id)"
    )

    private fun freshV10Connection(): Connection {
        val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        conn.createStatement().use { st ->
            st.execute(
                "CREATE TABLE calendar_layers (" +
                    "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                    "name TEXT NOT NULL, " +
                    "colorArgb INTEGER NOT NULL, " +
                    "visible INTEGER NOT NULL, " +
                    "isDefault INTEGER NOT NULL, " +
                    "position INTEGER NOT NULL, " +
                    "createdAt INTEGER NOT NULL)"
            )
            st.execute(
                "CREATE TABLE calendar_entries (" +
                    "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                    "uid TEXT NOT NULL, " +
                    "type TEXT NOT NULL, " +
                    "title TEXT NOT NULL, " +
                    "description TEXT, " +
                    "location TEXT, " +
                    "startMillis INTEGER, " +
                    "endMillis INTEGER, " +
                    "allDay INTEGER NOT NULL, " +
                    "completed INTEGER NOT NULL, " +
                    "recurrenceFrequency TEXT NOT NULL, " +
                    "recurrenceInterval INTEGER NOT NULL, " +
                    "recurrenceUntilMillis INTEGER, " +
                    "layerId INTEGER NOT NULL, " +
                    "isImportant INTEGER NOT NULL, " +
                    "comments TEXT, " +
                    "listId INTEGER, " +
                    "position INTEGER NOT NULL, " +
                    "colorArgb INTEGER, " +
                    "createdAt INTEGER NOT NULL, " +
                    "updatedAt INTEGER NOT NULL)"
            )
            st.execute(
                "CREATE TABLE reminders (" +
                    "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                    "entryId INTEGER NOT NULL, " +
                    "offsetMinutesBefore INTEGER NOT NULL)"
            )
        }
        return conn
    }

    private fun runMigration(conn: Connection) {
        conn.createStatement().use { st -> statements.forEach { st.execute(it) } }
    }

    private fun ResultSet.requireNext(): ResultSet {
        assertTrue("expected a row", next())
        return this
    }

    @Test
    fun `existing layer defaults to LOCAL kind with no subscription metadata and reminders off`() {
        val conn = freshV10Connection()
        conn.createStatement().use { st ->
            st.execute(
                "INSERT INTO calendar_layers (name, colorArgb, visible, isDefault, position, createdAt) " +
                    "VALUES ('Personal', 111, 1, 1, 0, 10)"
            )
        }
        runMigration(conn)

        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT * FROM calendar_layers").requireNext()
            assertEquals("LOCAL", rs.getString("kind"))
            rs.getObject("subscriptionUrl"); assertTrue(rs.wasNull())
            rs.getObject("lastSyncedAt"); assertTrue(rs.wasNull())
            rs.getObject("lastSyncError"); assertTrue(rs.wasNull())
            assertEquals("", rs.getString("reminderOffsetsMinutes"))
        }
    }

    @Test
    fun `a fresh row can set every new layer column`() {
        val conn = freshV10Connection()
        runMigration(conn)
        conn.createStatement().use { st ->
            st.execute(
                "INSERT INTO calendar_layers (name, colorArgb, visible, isDefault, position, createdAt, " +
                    "kind, subscriptionUrl, lastSyncedAt, lastSyncError, reminderOffsetsMinutes) " +
                    "VALUES ('Holidays', 222, 1, 0, 1, 20, 'SUBSCRIBED', 'https://example.com/cal.ics', 30, NULL, '0,1440')"
            )
            val rs = st.executeQuery("SELECT * FROM calendar_layers WHERE name = 'Holidays'").requireNext()
            assertEquals("SUBSCRIBED", rs.getString("kind"))
            assertEquals("https://example.com/cal.ics", rs.getString("subscriptionUrl"))
            assertEquals(30L, rs.getLong("lastSyncedAt"))
            assertEquals("0,1440", rs.getString("reminderOffsetsMinutes"))
        }
    }

    @Test
    fun `existing per-entry reminders are backfilled into individualReminderOffsetsMinutes`() {
        val conn = freshV10Connection()
        conn.createStatement().use { st ->
            st.execute(
                "INSERT INTO calendar_entries (uid, type, title, startMillis, allDay, completed, " +
                    "recurrenceFrequency, recurrenceInterval, layerId, isImportant, position, createdAt, updatedAt) " +
                    "VALUES ('uid-1', 'EVENT', 'Dentist', 1000, 0, 0, 'NONE', 1, 1, 0, 0, 10, 10)"
            )
            st.execute("INSERT INTO reminders (entryId, offsetMinutesBefore) VALUES (1, 30)")
            st.execute("INSERT INTO reminders (entryId, offsetMinutesBefore) VALUES (1, 1440)")
        }
        runMigration(conn)

        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT * FROM calendar_entries WHERE uid = 'uid-1'").requireNext()
            val backfilled = rs.getString("individualReminderOffsetsMinutes")
            assertEquals(setOf(30, 1440), backfilled.split(",").map { it.toInt() }.toSet())
        }
    }

    @Test
    fun `an entry with no reminders backfills to null`() {
        val conn = freshV10Connection()
        conn.createStatement().use { st ->
            st.execute(
                "INSERT INTO calendar_entries (uid, type, title, startMillis, allDay, completed, " +
                    "recurrenceFrequency, recurrenceInterval, layerId, isImportant, position, createdAt, updatedAt) " +
                    "VALUES ('uid-2', 'EVENT', 'No reminders', 2000, 0, 0, 'NONE', 1, 1, 0, 0, 10, 10)"
            )
        }
        runMigration(conn)

        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT * FROM calendar_entries WHERE uid = 'uid-2'").requireNext()
            rs.getObject("individualReminderOffsetsMinutes")
            assertTrue(rs.wasNull())
        }
    }

    @Test
    fun `two different entries each backfill only their own reminders`() {
        val conn = freshV10Connection()
        conn.createStatement().use { st ->
            st.execute(
                "INSERT INTO calendar_entries (uid, type, title, startMillis, allDay, completed, " +
                    "recurrenceFrequency, recurrenceInterval, layerId, isImportant, position, createdAt, updatedAt) " +
                    "VALUES ('uid-a', 'EVENT', 'A', 1000, 0, 0, 'NONE', 1, 1, 0, 0, 10, 10)"
            )
            st.execute(
                "INSERT INTO calendar_entries (uid, type, title, startMillis, allDay, completed, " +
                    "recurrenceFrequency, recurrenceInterval, layerId, isImportant, position, createdAt, updatedAt) " +
                    "VALUES ('uid-b', 'EVENT', 'B', 2000, 0, 0, 'NONE', 1, 1, 0, 0, 10, 10)"
            )
            st.execute("INSERT INTO reminders (entryId, offsetMinutesBefore) VALUES (1, 5)")
            st.execute("INSERT INTO reminders (entryId, offsetMinutesBefore) VALUES (2, 60)")
        }
        runMigration(conn)

        conn.createStatement().use { st ->
            val a = st.executeQuery("SELECT * FROM calendar_entries WHERE uid = 'uid-a'").requireNext()
            assertEquals("5", a.getString("individualReminderOffsetsMinutes"))
            val b = st.executeQuery("SELECT * FROM calendar_entries WHERE uid = 'uid-b'").requireNext()
            assertEquals("60", b.getString("individualReminderOffsetsMinutes"))
        }
    }
}
