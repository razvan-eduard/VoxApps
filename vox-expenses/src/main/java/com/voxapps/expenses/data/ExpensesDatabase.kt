package com.voxapps.expenses.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [Expense::class, Category::class, ExpenseLineItem::class, SpendingLimit::class],
    version = 1,
    exportSchema = false
)
abstract class ExpensesDatabase : RoomDatabase() {
    abstract fun expenseDao(): ExpenseDao
    abstract fun categoryDao(): CategoryDao
    abstract fun expenseLineItemDao(): ExpenseLineItemDao
    abstract fun spendingLimitDao(): SpendingLimitDao

    companion object {
        @Volatile private var instance: ExpensesDatabase? = null

        /** Room backed by SQLCipher; passphrase comes from the Keystore-backed store. */
        fun get(context: Context): ExpensesDatabase = instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

        private fun build(context: Context): ExpensesDatabase {
            // sqlcipher-android (new edition) does NOT auto-load its native lib — do it explicitly
            // before opening, or SQLiteConnection.nativeOpen throws UnsatisfiedLinkError.
            System.loadLibrary("sqlcipher")
            val factory = SupportOpenHelperFactory(DbKey.getOrCreatePassphrase(context))
            return Room.databaseBuilder(context, ExpensesDatabase::class.java, "vox-expenses.db")
                .openHelperFactory(factory)
                .build()
        }
    }
}
