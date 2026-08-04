package com.voxapps.calendarapp.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.voxapps.attachments.AttachmentDao
import com.voxapps.attachments.AttachmentEntity
import com.voxapps.ipc.PendingLlmRequestDao
import com.voxapps.ipc.PendingLlmRequestEntity
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [CalendarLayer::class, CalendarEntry::class, CalendarEntryTag::class, CalendarEntryTombstone::class,
        PendingLlmRequestEntity::class, AttachmentEntity::class, CalendarReminder::class,
        ToDoList::class],
    version = 10,
    exportSchema = false
)
@TypeConverters(CalendarConverters::class)
abstract class CalendarDatabase : RoomDatabase() {
    abstract fun calendarLayerDao(): CalendarLayerDao
    abstract fun calendarEntryDao(): CalendarEntryDao
    abstract fun calendarEntryTagDao(): CalendarEntryTagDao
    abstract fun pendingLlmRequestDao(): PendingLlmRequestDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun calendarReminderDao(): CalendarReminderDao
    abstract fun toDoListDao(): ToDoListDao

    companion object {
        @Volatile private var instance: CalendarDatabase? = null

        // Backs the peer-to-peer sync merge — CalendarEntry already had a stable uid/updatedAt pair
        // (unlike Expense/Note, which needed those added), so this migration is just the new
        // tombstone table, no column backfill needed.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS calendar_entry_tombstones (uid TEXT NOT NULL PRIMARY KEY, deletedAt INTEGER NOT NULL)"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS pending_llm_requests (" +
                        "requestId TEXT NOT NULL PRIMARY KEY, " +
                        "payloadJson TEXT NOT NULL, " +
                        "targetPackage TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "attemptCount INTEGER NOT NULL, " +
                        "lastAttemptAt INTEGER NOT NULL)"
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS attachments (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "recordType TEXT NOT NULL, " +
                        "recordId INTEGER NOT NULL, " +
                        "fileName TEXT NOT NULL, " +
                        "source TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_attachments_recordType_recordId ON attachments(recordType, recordId)"
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS reminders (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "entryId INTEGER NOT NULL, " +
                        "offsetMinutesBefore INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_reminders_entryId ON reminders(entryId)"
                )
            }
        }

        // Lets several photos captured/picked in one burst/selection be tied together as a single
        // multi-page attachment group (see AttachmentEntity's doc comment) — null groupId (every
        // pre-existing row) means "a group of one", unchanged behavior.
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE attachments ADD COLUMN groupId TEXT")
                db.execSQL("ALTER TABLE attachments ADD COLUMN groupOrder INTEGER NOT NULL DEFAULT 0")
            }
        }

        // Adds recurrenceInterval (the "every N ___" step count for recurring entries/reminders — see
        // RecurrenceExpander) and the checklist to-do list tables (see ToDoList/ToDoItem's doc
        // comments). todo_items uses a real FK+CASCADE since it's a brand-new table (unlike reminders/
        // calendar_entries.layerId, which stay plain non-FK columns from earlier ALTER TABLEs).
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE calendar_entries ADD COLUMN recurrenceInterval INTEGER NOT NULL DEFAULT 1")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS todo_lists (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "uid TEXT NOT NULL, " +
                        "title TEXT NOT NULL, " +
                        "layerId INTEGER NOT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_todo_lists_layerId ON todo_lists(layerId)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS todo_items (" +
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
                        "FOREIGN KEY(listId) REFERENCES todo_lists(id) ON DELETE CASCADE)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_todo_items_listId ON todo_items(listId)")
            }
        }

        // Adds per-list/per-item colorArgb (see ToDoList/ToDoItem's doc comments) for the node-timeline
        // UI redesign. Default backfills pre-existing rows to the palette's hue=0 preset — a real color
        // gets assigned going forward by ToDoRepository; existing rows just don't retroactively look
        // "randomly" colored relative to each other, same tradeoff every other ALTER-TABLE-added color
        // column in this codebase already makes (there is no way to run VoxColorPalette logic from raw
        // migration SQL).
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE todo_lists ADD COLUMN colorArgb INTEGER NOT NULL DEFAULT 4292436578")
                db.execSQL("ALTER TABLE todo_items ADD COLUMN colorArgb INTEGER NOT NULL DEFAULT 4292436578")
            }
        }

        // Adds ToDoItem.isImportant — a user-settable flag independent of dueMillis/done, surfaced as a
        // toggle in TaskEditDialog.
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE todo_items ADD COLUMN isImportant INTEGER NOT NULL DEFAULT 0")
            }
        }

        // Unifies ToDoItem into CalendarEntry: a to-do checklist item is now just a CalendarEntry row
        // with listId set, rather than its own table linked via an optional "shadow" CalendarEntry
        // (see ToDoRepository's prior doc comments) — one row, one source of truth, no more sync glue.
        // SQLite can't relax startMillis' NOT NULL via plain ALTER TABLE (a to-do item frequently has
        // no due date), so this rebuilds calendar_entries with the new nullable startMillis plus the
        // to-do fields (listId/position/isImportant/comments/colorArgb) folded in, then retires
        // todo_items entirely. The actual statements live in [Migration9to10Sql] (framework-agnostic —
        // plain strings, no Room/Android types) so a JVM test can replay them against a real SQLite
        // connection without needing Robolectric.
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Migration9to10Sql.STATEMENTS.forEach { db.execSQL(it) }
            }
        }

        /** Room backed by SQLCipher; passphrase comes from the Keystore-backed store. */
        fun get(context: Context): CalendarDatabase = instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

        private fun build(context: Context): CalendarDatabase {
            // sqlcipher-android (new edition) does NOT auto-load its native lib — do it explicitly
            // before opening, or SQLiteConnection.nativeOpen throws UnsatisfiedLinkError.
            System.loadLibrary("sqlcipher")
            val factory = SupportOpenHelperFactory(DbKey.getOrCreatePassphrase(context))
            return Room.databaseBuilder(context, CalendarDatabase::class.java, "vox-calendar.db")
                .openHelperFactory(factory)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
                .build()
        }
    }
}
