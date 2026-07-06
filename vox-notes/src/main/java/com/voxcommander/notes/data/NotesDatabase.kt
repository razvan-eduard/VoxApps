package com.voxcommander.notes.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory

@Database(entities = [Note::class], version = 1, exportSchema = false)
abstract class NotesDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao

    companion object {
        @Volatile private var instance: NotesDatabase? = null

        /** Room backed by SQLCipher; passphrase comes from the Keystore-backed store. */
        fun get(context: Context): NotesDatabase = instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

        private fun build(context: Context): NotesDatabase {
            SQLiteDatabase.loadLibs(context)
            val factory = SupportFactory(DbKey.getOrCreatePassphrase(context))
            return Room.databaseBuilder(context, NotesDatabase::class.java, "vox-notes.db")
                .openHelperFactory(factory)
                .build()
        }
    }
}
