package com.voxapps.notes.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(entities = [Note::class, Category::class], version = 2, exportSchema = false)
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
                .addMigrations(MIGRATION_1_2)
                .build()
        }
    }
}
