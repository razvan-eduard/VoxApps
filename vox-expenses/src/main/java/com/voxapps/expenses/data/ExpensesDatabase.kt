package com.voxapps.expenses.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.migration.Migration
import com.voxapps.ipc.PendingLlmRequestDao
import com.voxapps.ipc.PendingLlmRequestEntity
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [Expense::class, Category::class, ExpenseLineItem::class, SpendingLimit::class,
        ExpenseTombstone::class, MerchantCategoryMemory::class, PendingLlmRequestEntity::class],
    version = 9,
    exportSchema = false
)
@TypeConverters(ExpensesConverters::class)
abstract class ExpensesDatabase : RoomDatabase() {
    abstract fun expenseDao(): ExpenseDao
    abstract fun categoryDao(): CategoryDao
    abstract fun expenseLineItemDao(): ExpenseLineItemDao
    abstract fun spendingLimitDao(): SpendingLimitDao
    abstract fun merchantCategoryMemoryDao(): MerchantCategoryMemoryDao
    abstract fun pendingLlmRequestDao(): PendingLlmRequestDao

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

        // Backfills existing rows to 0, deliberately, not System.currentTimeMillis(): 0 preserves
        // the exact pre-migration import-delete behavior (0 <= any real exported_at, so old rows
        // stay unconditionally replaceable) — backfilling to "now" would make pre-migration rows
        // look artificially new and risks duplicate rows if a user upgrades and immediately restores
        // a backup that legitimately represents those same rows (see ExpensesExportImportHandler's
        // import() doc comment on the createdAt-filtered delete).
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE expenses ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE spending_limits ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        // Backs the peer-to-peer sync merge (see Expense's doc comment): every existing row needs a
        // distinct stable uid, not the shared '' the ADD COLUMN default leaves behind. SQLite has no
        // UUID() builtin, so this generates a v4-shaped id per row directly in SQL — randomblob()/
        // random() are re-evaluated for every row an unfiltered UPDATE touches, unlike a Kotlin-side
        // loop this needs no separate SELECT-then-N-UPDATEs round trip.
        private const val SQL_GENERATE_UUID = """
            lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' ||
            substr(lower(hex(randomblob(2))), 2) || '-' ||
            substr('89ab', abs(random()) % 4 + 1, 1) || substr(lower(hex(randomblob(2))), 2) ||
            '-' || lower(hex(randomblob(6)))
        """

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE expenses ADD COLUMN uid TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE expenses ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE expenses SET uid = $SQL_GENERATE_UUID WHERE uid = ''")
                db.execSQL("UPDATE expenses SET updatedAt = createdAt WHERE updatedAt = 0")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_expenses_uid ON expenses(uid)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS expense_tombstones (uid TEXT NOT NULL PRIMARY KEY, deletedAt INTEGER NOT NULL)"
                )
            }
        }

        // Every existing row genuinely is an outgoing expense (there was no way to record anything
        // else before this column existed), so this backfill is exact rather than a best-effort guess.
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE expenses ADD COLUMN direction TEXT NOT NULL DEFAULT 'OUTGOING'")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS merchant_category_memory (" +
                        "vendorKey TEXT NOT NULL PRIMARY KEY, " +
                        "categoryId INTEGER NOT NULL, " +
                        "consecutiveCount INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL)"
                )
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
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

        fun get(context: Context): ExpensesDatabase = instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

        private fun build(context: Context): ExpensesDatabase {
            System.loadLibrary("sqlcipher")
            val factory = SupportOpenHelperFactory(DbKey.getOrCreatePassphrase(context))
            return Room.databaseBuilder(context, ExpensesDatabase::class.java, "vox-expenses.db")
                .openHelperFactory(factory)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                .build()
        }
    }
}
