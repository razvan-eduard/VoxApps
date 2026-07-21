package com.voxapps.notes.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(entities = [Note::class, Category::class, NoteTombstone::class], version = 3, exportSchema = false)
abstract class NotesDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun categoryDao(): CategoryDao

    companion object {
        @Volatile private var instance: NotesDatabase? = null

        /** v1 (notes only) → v2: add categories table + notes.categoryId (nullable FK). Preserves data. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS categories (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL,
                        colorArgb INTEGER NOT NULL,
                        position INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("ALTER TABLE notes ADD COLUMN categoryId INTEGER")
                db.execSQL("ALTER TABLE notes ADD COLUMN title TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_notes_categoryId ON notes(categoryId)")
            }
        }

        // Backs the peer-to-peer sync merge (see Note's doc comment): every existing row needs a
        // distinct stable uid, not the shared '' the ADD COLUMN default leaves behind. SQLite has no
        // UUID() builtin, so this generates a v4-shaped id per row directly in SQL (mirrors
        // vox-expenses' ExpensesDatabase.MIGRATION_5_6, same trick, same reasoning).
        private const val SQL_GENERATE_UUID = """
            lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' ||
            substr(lower(hex(randomblob(2))), 2) || '-' ||
            substr('89ab', abs(random()) % 4 + 1, 1) || substr(lower(hex(randomblob(2))), 2) ||
            '-' || lower(hex(randomblob(6)))
        """

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN uid TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE notes ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE notes SET uid = $SQL_GENERATE_UUID WHERE uid = ''")
                db.execSQL("UPDATE notes SET updatedAt = createdAt WHERE updatedAt = 0")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_notes_uid ON notes(uid)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS note_tombstones (uid TEXT NOT NULL PRIMARY KEY, deletedAt INTEGER NOT NULL)"
                )
            }
        }

        /** Room backed by SQLCipher; passphrase comes from the Keystore-backed store. */
        fun get(context: Context): NotesDatabase = instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

        private fun build(context: Context): NotesDatabase {
            // sqlcipher-android (new edition) does NOT auto-load its native lib — do it explicitly
            // before opening, or SQLiteConnection.nativeOpen throws UnsatisfiedLinkError.
            System.loadLibrary("sqlcipher")
            val factory = SupportOpenHelperFactory(DbKey.getOrCreatePassphrase(context))
            return Room.databaseBuilder(context, NotesDatabase::class.java, "vox-notes.db")
                .openHelperFactory(factory)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
        }
    }
}
