package com.voxapps.calendarapp.data

/**
 * The raw SQL statements for [CalendarDatabase]'s 9->10 migration (unifying `ToDoItem` into
 * `CalendarEntry` — see `MIGRATION_9_10`'s doc comment), factored out as plain strings with no
 * Room/Android dependency (`androidx.sqlite.db.SupportSQLiteDatabase` et al.) so a JVM unit test can
 * execute them via a real (JDBC) SQLite connection and assert on the resulting rows, without needing
 * Robolectric or any Android-framework test double — this repo avoids pulling those in for unit tests
 * wherever a plain-JVM alternative works (mirrors `org.json:json` standing in for `android.util.JSON*`
 * elsewhere). Order matters: each statement assumes the previous ones already ran.
 */
object Migration9to10Sql {
    val STATEMENTS: List<String> = listOf(
        "CREATE TABLE calendar_entries_new (" +
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
            "isImportant INTEGER NOT NULL DEFAULT 0, " +
            "comments TEXT, " +
            "listId INTEGER, " +
            "position INTEGER NOT NULL DEFAULT 0, " +
            "colorArgb INTEGER, " +
            "createdAt INTEGER NOT NULL, " +
            "updatedAt INTEGER NOT NULL)",

        "INSERT INTO calendar_entries_new (id, uid, type, title, description, location, startMillis, " +
            "endMillis, allDay, completed, recurrenceFrequency, recurrenceInterval, recurrenceUntilMillis, " +
            "layerId, isImportant, comments, listId, position, colorArgb, createdAt, updatedAt) " +
            "SELECT id, uid, type, title, description, location, startMillis, endMillis, allDay, completed, " +
            "recurrenceFrequency, recurrenceInterval, recurrenceUntilMillis, layerId, 0, NULL, NULL, 0, NULL, " +
            "createdAt, updatedAt FROM calendar_entries",

        // A dated to-do item already has a shadow CalendarEntry (linkedEntryId) — fold its to-do
        // fields into that existing row rather than duplicating it.
        "UPDATE calendar_entries_new SET " +
            "listId = (SELECT listId FROM todo_items WHERE todo_items.linkedEntryId = calendar_entries_new.id), " +
            "position = (SELECT position FROM todo_items WHERE todo_items.linkedEntryId = calendar_entries_new.id), " +
            "isImportant = (SELECT isImportant FROM todo_items WHERE todo_items.linkedEntryId = calendar_entries_new.id), " +
            "comments = (SELECT comments FROM todo_items WHERE todo_items.linkedEntryId = calendar_entries_new.id), " +
            "colorArgb = (SELECT colorArgb FROM todo_items WHERE todo_items.linkedEntryId = calendar_entries_new.id) " +
            "WHERE id IN (SELECT linkedEntryId FROM todo_items WHERE linkedEntryId IS NOT NULL)",

        // A dateless to-do item never had a shadow entry — insert a brand-new row for it, with
        // startMillis left NULL. lower(hex(randomblob(16))) mints a stable-enough pseudo-UUID for uid
        // (these rows are excluded from ICS export/sync, so its format doesn't matter beyond
        // uniqueness).
        "INSERT INTO calendar_entries_new (uid, type, title, description, location, startMillis, endMillis, " +
            "allDay, completed, recurrenceFrequency, recurrenceInterval, recurrenceUntilMillis, layerId, " +
            "isImportant, comments, listId, position, colorArgb, createdAt, updatedAt) " +
            "SELECT lower(hex(randomblob(16))), 'TASK', text, NULL, NULL, NULL, NULL, 0, done, 'NONE', 1, NULL, " +
            "(SELECT layerId FROM todo_lists WHERE todo_lists.id = todo_items.listId), isImportant, comments, " +
            "listId, position, colorArgb, createdAt, updatedAt " +
            "FROM todo_items WHERE linkedEntryId IS NULL",

        "DROP TABLE calendar_entries",
        "ALTER TABLE calendar_entries_new RENAME TO calendar_entries",
        "CREATE INDEX IF NOT EXISTS index_calendar_entries_layerId ON calendar_entries(layerId)",
        "CREATE INDEX IF NOT EXISTS index_calendar_entries_startMillis ON calendar_entries(startMillis)",
        "CREATE INDEX IF NOT EXISTS index_calendar_entries_listId ON calendar_entries(listId)",

        "DROP TABLE todo_items"
    )
}
