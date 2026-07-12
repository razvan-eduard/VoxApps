package com.voxapps.calendarapp.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [CalendarLayer::class, CalendarEntry::class, CalendarEntryTag::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(CalendarConverters::class)
abstract class CalendarDatabase : RoomDatabase() {
    abstract fun calendarLayerDao(): CalendarLayerDao
    abstract fun calendarEntryDao(): CalendarEntryDao
    abstract fun calendarEntryTagDao(): CalendarEntryTagDao

    companion object {
        @Volatile private var instance: CalendarDatabase? = null

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
                .build()
        }
    }
}
