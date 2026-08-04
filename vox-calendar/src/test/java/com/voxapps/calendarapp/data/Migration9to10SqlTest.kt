package com.voxapps.calendarapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * Replays the exact SQL of [CalendarDatabase]'s 9->10 migration ([Migration9to10Sql]) against a real,
 * fresh in-memory SQLite connection (`org.xerial:sqlite-jdbc` — pure JVM, no Robolectric/Android
 * needed) to verify it correctly folds `todo_items` into `calendar_entries` and preserves every field.
 * This repo has no existing Room `MigrationTestHelper` infra (no exported schema JSON history, no
 * `room-testing` dependency) — retrofitting 9 versions of schema just for this one migration would be
 * disproportionate, so this tests the same real SQL a different, lighter way instead.
 */
class Migration9to10SqlTest {

    /** Builds a fresh in-memory v9-shaped schema (calendar_entries/todo_lists/todo_items exactly as
     *  they existed right before this migration — see CalendarDatabase's MIGRATION_6_7/7_8/8_9). */
    private fun freshV9Connection(): Connection {
        val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        conn.createStatement().use { st ->
            st.execute(
                "CREATE TABLE calendar_entries (" +
                    "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                    "uid TEXT NOT NULL, " +
                    "type TEXT NOT NULL, " +
                    "title TEXT NOT NULL, " +
                    "description TEXT, " +
                    "location TEXT, " +
                    "startMillis INTEGER NOT NULL, " +
                    "endMillis INTEGER, " +
                    "allDay INTEGER NOT NULL, " +
                    "completed INTEGER NOT NULL, " +
                    "recurrenceFrequency TEXT NOT NULL, " +
                    "recurrenceInterval INTEGER NOT NULL, " +
                    "recurrenceUntilMillis INTEGER, " +
                    "layerId INTEGER NOT NULL, " +
                    "createdAt INTEGER NOT NULL, " +
                    "updatedAt INTEGER NOT NULL)"
            )
            st.execute(
                "CREATE TABLE todo_lists (" +
                    "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                    "uid TEXT NOT NULL, " +
                    "title TEXT NOT NULL, " +
                    "layerId INTEGER NOT NULL, " +
                    "createdAt INTEGER NOT NULL, " +
                    "updatedAt INTEGER NOT NULL, " +
                    "colorArgb INTEGER NOT NULL DEFAULT 4292436578)"
            )
            st.execute(
                "CREATE TABLE todo_items (" +
                    "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                    "listId INTEGER NOT NULL, " +
                    "text TEXT NOT NULL, " +
                    "position INTEGER NOT NULL, " +
                    "dueMillis INTEGER, " +
                    "done INTEGER NOT NULL, " +
                    "comments TEXT, " +
                    "linkedEntryId INTEGER, " +
                    "createdAt INTEGER NOT NULL, " +
                    "updatedAt INTEGER NOT NULL, " +
                    "colorArgb INTEGER NOT NULL DEFAULT 4292436578, " +
                    "isImportant INTEGER NOT NULL DEFAULT 0)"
            )
        }
        return conn
    }

    private fun runMigration(conn: Connection) {
        conn.createStatement().use { st ->
            Migration9to10Sql.STATEMENTS.forEach { sql -> st.execute(sql) }
        }
    }

    private fun ResultSet.requireNext(): ResultSet {
        assertTrue("expected a row", next())
        return this
    }

    @Test
    fun `plain event is carried over unchanged with default to-do fields`() {
        val conn = freshV9Connection()
        conn.createStatement().use { st ->
            st.execute(
                "INSERT INTO calendar_entries (uid, type, title, description, location, startMillis, endMillis, " +
                    "allDay, completed, recurrenceFrequency, recurrenceInterval, recurrenceUntilMillis, layerId, " +
                    "createdAt, updatedAt) VALUES ('uid-1', 'EVENT', 'Dentist', NULL, NULL, 1000, NULL, 0, 0, " +
                    "'NONE', 1, NULL, 1, 10, 10)"
            )
        }
        runMigration(conn)

        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT * FROM calendar_entries").requireNext()
            assertEquals("Dentist", rs.getString("title"))
            assertEquals(1000L, rs.getLong("startMillis"))
            rs.getObject("listId"); assertTrue(rs.wasNull())
            assertEquals(0L, rs.getLong("isImportant"))
            rs.getObject("colorArgb"); assertTrue(rs.wasNull())
            assertFalse(rs.next())
        }
    }

    @Test
    fun `dated to-do item with a shadow entry merges into that same row, not a duplicate`() {
        val conn = freshV9Connection()
        conn.createStatement().use { st ->
            st.execute(
                "INSERT INTO calendar_entries (uid, type, title, description, location, startMillis, endMillis, " +
                    "allDay, completed, recurrenceFrequency, recurrenceInterval, recurrenceUntilMillis, layerId, " +
                    "createdAt, updatedAt) VALUES ('uid-2', 'TASK', 'Buy bread', NULL, NULL, 2000, NULL, 0, 0, " +
                    "'NONE', 1, NULL, 1, 20, 20)"
            )
            st.execute(
                "INSERT INTO todo_lists (uid, title, layerId, createdAt, updatedAt, colorArgb) " +
                    "VALUES ('list-uid', 'Groceries', 1, 5, 5, 111)"
            )
            st.execute(
                "INSERT INTO todo_items (listId, text, position, dueMillis, done, comments, linkedEntryId, " +
                    "createdAt, updatedAt, colorArgb, isImportant) " +
                    "VALUES (1, 'Buy bread', 3, 2000, 0, 'urgent', 1, 20, 20, 222, 1)"
            )
        }
        runMigration(conn)

        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT * FROM calendar_entries").requireNext()
            assertEquals(1L, rs.getLong("listId"))
            assertEquals(3L, rs.getLong("position"))
            assertEquals(1L, rs.getLong("isImportant"))
            assertEquals("urgent", rs.getString("comments"))
            assertEquals(222L, rs.getLong("colorArgb"))
            assertEquals(2000L, rs.getLong("startMillis")) // unchanged from the original shadow entry
            assertFalse("no duplicate row should be created", rs.next())
        }
    }

    @Test
    fun `dateless to-do item becomes a new row with null startMillis, layerId from its list`() {
        val conn = freshV9Connection()
        conn.createStatement().use { st ->
            st.execute(
                "INSERT INTO todo_lists (uid, title, layerId, createdAt, updatedAt, colorArgb) " +
                    "VALUES ('list-uid', 'Groceries', 7, 5, 5, 111)"
            )
            st.execute(
                "INSERT INTO todo_items (listId, text, position, dueMillis, done, comments, linkedEntryId, " +
                    "createdAt, updatedAt, colorArgb, isImportant) " +
                    "VALUES (1, 'Buy milk', 0, NULL, 0, NULL, NULL, 30, 30, 333, 0)"
            )
        }
        runMigration(conn)

        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT * FROM calendar_entries").requireNext()
            assertEquals("Buy milk", rs.getString("title"))
            rs.getObject("startMillis"); assertTrue(rs.wasNull())
            assertEquals(1L, rs.getLong("listId"))
            assertEquals(7L, rs.getLong("layerId")) // pulled from the to-do list's own layerId
            assertEquals("TASK", rs.getString("type"))
            assertEquals(333L, rs.getLong("colorArgb"))
            assertFalse(rs.next())
        }
    }

    @Test
    fun `a completed dateless to-do item keeps its completed flag`() {
        val conn = freshV9Connection()
        conn.createStatement().use { st ->
            st.execute(
                "INSERT INTO todo_lists (uid, title, layerId, createdAt, updatedAt, colorArgb) " +
                    "VALUES ('list-uid', 'Groceries', 1, 5, 5, 111)"
            )
            st.execute(
                "INSERT INTO todo_items (listId, text, position, dueMillis, done, comments, linkedEntryId, " +
                    "createdAt, updatedAt, colorArgb, isImportant) " +
                    "VALUES (1, 'Done thing', 0, NULL, 1, NULL, NULL, 30, 30, 333, 0)"
            )
        }
        runMigration(conn)

        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT * FROM calendar_entries").requireNext()
            assertEquals(1L, rs.getLong("completed"))
        }
    }

    @Test
    fun `todo_items table no longer exists after migration`() {
        val conn = freshV9Connection()
        runMigration(conn)
        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='todo_items'")
            assertFalse(rs.next())
        }
    }

    @Test
    fun `a plain event and a to-do item coexist without cross-contamination`() {
        val conn = freshV9Connection()
        conn.createStatement().use { st ->
            st.execute(
                "INSERT INTO calendar_entries (uid, type, title, description, location, startMillis, endMillis, " +
                    "allDay, completed, recurrenceFrequency, recurrenceInterval, recurrenceUntilMillis, layerId, " +
                    "createdAt, updatedAt) VALUES ('uid-1', 'EVENT', 'Dentist', NULL, NULL, 1000, NULL, 0, 0, " +
                    "'NONE', 1, NULL, 1, 10, 10)"
            )
            st.execute(
                "INSERT INTO todo_lists (uid, title, layerId, createdAt, updatedAt, colorArgb) " +
                    "VALUES ('list-uid', 'Groceries', 1, 5, 5, 111)"
            )
            st.execute(
                "INSERT INTO todo_items (listId, text, position, dueMillis, done, comments, linkedEntryId, " +
                    "createdAt, updatedAt, colorArgb, isImportant) " +
                    "VALUES (1, 'Buy milk', 0, NULL, 0, NULL, NULL, 30, 30, 333, 0)"
            )
        }
        runMigration(conn)

        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT COUNT(*) AS c FROM calendar_entries").requireNext()
            assertEquals(2L, rs.getLong("c"))
            val eventRs = st.executeQuery("SELECT * FROM calendar_entries WHERE title = 'Dentist'").requireNext()
            eventRs.getObject("listId"); assertTrue(eventRs.wasNull())
            val todoRs = st.executeQuery("SELECT * FROM calendar_entries WHERE title = 'Buy milk'").requireNext()
            assertEquals(1L, todoRs.getLong("listId"))
        }
    }
}
