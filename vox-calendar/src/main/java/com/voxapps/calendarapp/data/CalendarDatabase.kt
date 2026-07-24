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
        PendingLlmRequestEntity::class, AttachmentEntity::class],
    version = 4,
    exportSchema = false
)
@TypeConverters(CalendarConverters::class)
abstract class CalendarDatabase : RoomDatabase() {
    abstract fun calendarLayerDao(): CalendarLayerDao
    abstract fun calendarEntryDao(): CalendarEntryDao
    abstract fun calendarEntryTagDao(): CalendarEntryTagDao
    abstract fun pendingLlmRequestDao(): PendingLlmRequestDao
    abstract fun attachmentDao(): AttachmentDao

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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
        }
    }
}
