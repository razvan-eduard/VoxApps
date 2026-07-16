package com.voxapps.expenses.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.migration.Migration
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [Expense::class, Category::class, ExpenseLineItem::class, SpendingLimit::class],
    version = 4,
    exportSchema = false
)
abstract class ExpensesDatabase : RoomDatabase() {
    abstract fun expenseDao(): ExpenseDao
    abstract fun categoryDao(): CategoryDao
    abstract fun expenseLineItemDao(): ExpenseLineItemDao
    abstract fun spendingLimitDao(): SpendingLimitDao

    companion object {
        @Volatile private var instance: ExpensesDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE expense_line_items ADD COLUMN netAmount REAL")
                db.execSQL("ALTER TABLE expense_line_items ADD COLUMN vatAmount REAL")
                db.execSQL("ALTER TABLE expense_line_items ADD COLUMN grossAmount REAL")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE expenses ADD COLUMN receiptImageName TEXT")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE expenses ADD COLUMN isStub INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun get(context: Context): ExpensesDatabase = instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

        private fun build(context: Context): ExpensesDatabase {
            System.loadLibrary("sqlcipher")
            val factory = SupportOpenHelperFactory(DbKey.getOrCreatePassphrase(context))
            return Room.databaseBuilder(context, ExpensesDatabase::class.java, "vox-expenses.db")
                .openHelperFactory(factory)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
        }
    }
}
