package com.voxapps.expenses.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.migration.Migration
import com.voxapps.attachments.AttachmentDao
import com.voxapps.datahygiene.CategoryFallback
import com.voxapps.datahygiene.NameCasing
import com.voxapps.attachments.AttachmentEntity
import com.voxapps.attachments.AttachmentSource
import com.voxapps.fieldmemory.LearnedFieldCorrection
import com.voxapps.fieldmemory.LearnedFieldCorrectionDao
import com.voxapps.ipc.PendingLlmRequestDao
import com.voxapps.ipc.PendingLlmRequestEntity
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [Expense::class, Category::class, ExpenseLineItem::class, SpendingLimit::class,
        ExpenseTombstone::class, PendingLlmRequestEntity::class,
        AttachmentEntity::class, DuplicateRuleEntity::class, com.voxapps.suggestions.FieldSuggestion::class,
        LearnedFieldCorrection::class, RemapRuleEntity::class, RemapPatternSighting::class,
        RecurringPayment::class, BankAccount::class],
    version = 34,
    exportSchema = false
)
@TypeConverters(ExpensesConverters::class)
abstract class ExpensesDatabase : RoomDatabase() {
    abstract fun expenseDao(): ExpenseDao
    abstract fun categoryDao(): CategoryDao
    abstract fun expenseLineItemDao(): ExpenseLineItemDao
    abstract fun spendingLimitDao(): SpendingLimitDao
    abstract fun recurringPaymentDao(): RecurringPaymentDao
    abstract fun bankAccountDao(): BankAccountDao
    abstract fun remapRuleDao(): RemapRuleDao
    abstract fun remapPatternSightingDao(): RemapPatternSightingDao
    abstract fun pendingLlmRequestDao(): PendingLlmRequestDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun duplicateRuleDao(): DuplicateRuleDao
    abstract fun fieldSuggestionDao(): com.voxapps.suggestions.FieldSuggestionDao
    abstract fun learnedFieldCorrectionDao(): LearnedFieldCorrectionDao

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

        private val MIGRATION_9_10 = object : Migration(9, 10) {
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

        // Creates the rule engine's table and seeds two default rules reproducing the previous
        // hardcoded near-duplicate behavior (amount+currency+direction+time-window always required,
        // plus title-or-vendor) as two AND rules OR'd together — see ExpenseRuleFields for what each
        // fieldId means. Deliberately does NOT also seed the old ExpenseDuplicateChecker's separate
        // all-fields-exact/same-day rule — that behavior is intentionally not preserved 1:1 now that
        // both layers fold into one rule engine with a single shared time window; a user who wants
        // that strictness back can build it themselves with the new rules UI.
        private fun seedDefaultDuplicateRules(db: SupportSQLiteDatabase) {
            db.execSQL(
                "INSERT INTO duplicate_rules (name, fieldIds, combinator, enabled, sortOrder, appliesAutomatically, fuzzyMatchEnabled) VALUES " +
                    "('Same amount & title', 'totalAmount,currencyCode,direction,dateTime,title', 'AND', 1, 0, 1, 1)"
            )
            db.execSQL(
                "INSERT INTO duplicate_rules (name, fieldIds, combinator, enabled, sortOrder, appliesAutomatically, fuzzyMatchEnabled) VALUES " +
                    "('Same amount & vendor', 'totalAmount,currencyCode,direction,dateTime,vendor', 'AND', 1, 1, 1, 1)"
            )
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS duplicate_rules (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "name TEXT NOT NULL, " +
                        "fieldIds TEXT NOT NULL, " +
                        "combinator TEXT NOT NULL, " +
                        "enabled INTEGER NOT NULL, " +
                        "sortOrder INTEGER NOT NULL)"
                )
            }
        }

        // Adds per-rule control that didn't exist when duplicate_rules was first created: whether a
        // rule applies automatically at insert time (vs. review-only) and whether its string fields
        // fuzzy-match — both were briefly a single global setting each before shipping, moved to
        // per-rule immediately (no real users on the old shape yet), hence bundled into one migration
        // rather than the usual one-column-per-release cadence.
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE duplicate_rules ADD COLUMN appliesAutomatically INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE duplicate_rules ADD COLUMN fuzzyMatchEnabled INTEGER NOT NULL DEFAULT 1")
                // MIGRATION_10_11 seeded the two default rules without these columns (they didn't
                // exist yet) — seed again only if that row genuinely never ran (empty table), so an
                // upgrade from an already-seeded v11 doesn't duplicate them.
                val cursor = db.query("SELECT COUNT(*) FROM duplicate_rules")
                val isEmpty = cursor.use { it.moveToFirst() && it.getInt(0) == 0 }
                if (isEmpty) seedDefaultDuplicateRules(db)
            }
        }

        // Feeds Expense.dataScore() (see ExpenseDataScore.kt) — which of two duplicate candidates has
        // the better data, for picking a merge winner instead of always trusting whichever record
        // arrived first. Existing rows backfill to MANUAL/false: a reasonable "unknown, treat as
        // baseline" default, no worse than the no-scoring behavior every row had before this existed.
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE expenses ADD COLUMN source TEXT NOT NULL DEFAULT 'MANUAL'")
                db.execSQL("ALTER TABLE expenses ADD COLUMN manuallyEdited INTEGER NOT NULL DEFAULT 0")
            }
        }

        // Feeds the tappable field-suggestion chips shown after a photo is rescanned for line items
        // on an already-saved expense (see PendingFieldSuggestion's doc comment) — one row per
        // expense, upserted on every rescan, cleared on save.
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS pending_field_suggestions (" +
                        "expenseId INTEGER NOT NULL PRIMARY KEY, " +
                        "title TEXT, vendor TEXT, bank TEXT, totalAmount REAL, currencyCode TEXT, " +
                        "category TEXT, location TEXT, dateTime INTEGER)"
                )
            }
        }

        // Line items move into the same review-and-apply suggestion flow as every other rescanned
        // field, instead of being written directly (see PendingFieldSuggestion's doc comment for why
        // that made an already-open ExpenseEditScreen look stale after a rescan).
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pending_field_suggestions ADD COLUMN itemsJson TEXT")
            }
        }

        // Lets several photos captured/picked in one burst/selection be tied together as a single
        // multi-page document (see AttachmentEntity's doc comment) — null groupId (every pre-existing
        // row) means "a group of one", unchanged behavior.
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE attachments ADD COLUMN groupId TEXT")
                db.execSQL("ALTER TABLE attachments ADD COLUMN groupOrder INTEGER NOT NULL DEFAULT 0")
            }
        }

        // Lets a line-items rescan suggestion remember which attachment group (if any) triggered it,
        // so dismissing the suggestion can also remove the scan that produced it instead of leaving
        // the photos permanently attached with no suggestion left to apply them from.
        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pending_field_suggestions ADD COLUMN sourceGroupId TEXT")
            }
        }

        // No schema change — Expense.receiptImageName stays as a denormalized pointer, but the
        // scanned receipt's file lifetime is now tracked by a row in `attachments` (source=SCANNED)
        // instead of a bespoke column-based guard, same as every other attachment. Backfills one row
        // per existing expense that already has a receipt, so pre-migration data gets the same
        // reference-counted delete protection as anything created after this update.
        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "INSERT INTO attachments (recordType, recordId, fileName, source, createdAt) " +
                        "SELECT '${ExpensesAttachments.RECORD_TYPE}', id, receiptImageName, " +
                        "'${AttachmentSource.SCANNED}', createdAt FROM expenses WHERE receiptImageName IS NOT NULL"
                )
            }
        }

        // Backs the word-level correction memory (see LearnedFieldCorrection in :core:fieldmemory);
        // pending_field_suggestions gains a comments column because a correction can target the
        // comments field, which the rescan flow never suggested for.
        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS learned_field_corrections (" +
                        "garbageKey TEXT NOT NULL PRIMARY KEY, " +
                        "fix TEXT NOT NULL, " +
                        "consecutiveCount INTEGER NOT NULL, " +
                        "quarantined INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL)"
                )
                db.execSQL("ALTER TABLE pending_field_suggestions ADD COLUMN comments TEXT")
            }
        }

        // The re-map rule engine's table (see RemapRuleEntity) absorbs merchant_category_memory:
        // every learned vendor→category row becomes a LEARNED rule with match={vendor} and
        // set={categoryId}, count carried, so nothing the user taught is forgotten. The row
        // transform runs in Kotlin with org.json doing the encoding — vendorKey is free text, and
        // JSON-escaping it in SQL string functions is exactly the kind of almost-right that
        // corrupts one row in a thousand.
        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS remap_rules (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "name TEXT NOT NULL, " +
                        "matchJson TEXT NOT NULL, " +
                        "setJson TEXT NOT NULL, " +
                        "origin TEXT NOT NULL, " +
                        "consecutiveCount INTEGER NOT NULL, " +
                        "enabled INTEGER NOT NULL, " +
                        "sortOrder INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL)"
                )
                val cursor = db.query("SELECT vendorKey, categoryId, consecutiveCount, updatedAt FROM merchant_category_memory")
                cursor.use {
                    while (it.moveToNext()) {
                        val vendorKey = it.getString(0)
                        val match = org.json.JSONObject().put("vendor", vendorKey).toString()
                        val set = org.json.JSONObject().put("categoryId", it.getLong(1).toString()).toString()
                        db.execSQL(
                            "INSERT INTO remap_rules (name, matchJson, setJson, origin, consecutiveCount, enabled, sortOrder, updatedAt) " +
                                "VALUES (?, ?, ?, 'LEARNED', ?, 1, 0, ?)",
                            arrayOf(vendorKey, match, set, it.getInt(2), it.getLong(3))
                        )
                    }
                }
                db.execSQL("DROP TABLE merchant_category_memory")
            }
        }

        // Per-match-field fuzziness for re-map rules (see RemapRuleEntity.fuzzJson) — every
        // existing rule backfills to '{}', i.e. exact matching, the only behavior that existed.
        private val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE remap_rules ADD COLUMN fuzzJson TEXT NOT NULL DEFAULT '{}'")
            }
        }

        // The learner becomes proposals-only (see RemapPatternSighting): auto-activating LEARNED
        // rules are gone, so the streak column goes with them. Rules that had reached activation
        // under the old default threshold convert to plain USER rules — what the user taught keeps
        // working; sub-threshold streaks never answered anything and are dropped. Table rebuilt
        // (SQLite can't drop a column here), also shedding the v21 DEFAULT clause so the table
        // matches the entity declaration exactly.
        // Invoice-only extra totals (see Expense.previousBalanceAmount/totalToPayAmount).
        /**
         * The fallback category. Existing installs get the flag on the lowest-positioned category
         * rather than on none: a fallback that is null until somebody visits Settings would make a
         * model-free scan fail on exactly the installs that upgraded into the feature.
         */
        /** The invoice's net and tax, beside the total it already stored. */
        /**
         * The per-expense suggestion row becomes one row per suggested field.
         *
         * Carried across rather than dropped: a rescan someone has not looked at yet is exactly the
         * thing this table exists to hold, and losing it on an upgrade would lose the photographs'
         * only remaining purpose too — the source tag is what lets dismissing the last suggestion
         * remove them. Every column becomes a key, `NULL`s excepted; a row that held nothing but its
         * id carries nothing over.
         */
        /**
         * A category for records nothing classified, and it becomes the fallback.
         *
         * Every capture with no opinion about its category takes the fallback, so whatever holds
         * that role is stamped on a great many records that have nothing to do with it — and
         * afterwards a stamped record cannot be told from one that genuinely belongs there. A
         * fallback naming its own emptiness is the only one that stays honest at that volume.
         *
         * It takes the role unconditionally, including from a category that already held it: any
         * category with a meaning of its own is the wrong answer to "nothing chose one". Position -1
         * so it also wins the ordering fallback if its star is ever moved, and grey rather than a
         * hue because it is the absence of a category rather than one more of them. Moving the star
         * elsewhere is one tap in Categories.
         */
        private fun seedUncategorised(db: SupportSQLiteDatabase) {
            // Exactly one carries the star, and a moment with none is as illegal as a moment with
            // two — which is why the statements come as a set from the one place that defines what
            // a fallback category is.
            CategoryFallback.seedStatements(createdAt = System.currentTimeMillis())
                .forEach { db.execSQL(it) }
        }

        /**
         * The fallback category and the casing of every category name, applied again.
         *
         * Both steps are idempotent — the insert is conditional on the row's absence, the star ends
         * up in exactly one place, and a cased name cases to itself — so running them from either
         * version reaches the same state, and an upgrade crossing both runs the chain once.
         */
        /**
         * Cards and accounts money moves through, and the link from a record to one.
         *
         * The digits are unique: two rows for one card would let the same account collect records
         * under both, and nothing downstream could tell they were the same. See [BankAccount] for
         * why a card is held by its tail rather than in full, and for the one optional level of
         * nesting a card may sit at under an account.
         */
        /** Whether a rule asks to be told when it fires — see [RemapRuleEntity.alertEnabled]. */
        private val MIGRATION_33_34 = object : Migration(33, 34) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE remap_rules ADD COLUMN alertEnabled INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** What a proposal's records had in common besides the merchant, kept so the editor can
         *  offer it — see [RemapRuleEntity.suggestJson]. */
        private val MIGRATION_32_33 = object : Migration(32, 33) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE remap_rules ADD COLUMN suggestJson TEXT NOT NULL DEFAULT '{}'")
                db.execSQL("ALTER TABLE remap_pattern_sightings ADD COLUMN observedJson TEXT NOT NULL DEFAULT '{}'")
            }
        }

        private val MIGRATION_31_32 = object : Migration(31, 32) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS bank_accounts (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "digits TEXT NOT NULL, " +
                        "kind TEXT NOT NULL, " +
                        "parentId INTEGER, " +
                        "label TEXT, " +
                        "currencyCode TEXT NOT NULL, " +
                        "bankName TEXT, " +
                        "icon TEXT, " +
                        "createdAt INTEGER NOT NULL, " +
                        "autoCreated INTEGER NOT NULL DEFAULT 0)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_bank_accounts_digits ON bank_accounts(digits)")
                db.execSQL("ALTER TABLE expenses ADD COLUMN bankAccountId INTEGER")
            }
        }

        /** A category may carry a short piece of text identifying it — see [Category.icon]. */
        private val MIGRATION_30_31 = object : Migration(30, 31) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE categories ADD COLUMN icon TEXT")
            }
        }

        private val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(db: SupportSQLiteDatabase) {
                seedUncategorised(db)
                titleCaseCategories(db)
            }
        }

        private val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                seedUncategorised(db)
                titleCaseCategories(db)
            }
        }

        /**
         * Existing names take the shape new ones are written in.
         *
         * In Kotlin rather than SQL because SQLite's `upper`/`lower` only know ASCII, and every
         * Romanian name carrying a diacritic would come back mangled — a category renamed wrongly is
         * worse than one cased inconsistently. A name that would collide with one already there is
         * left as it is: merging two categories moves records between them, which is the auto-merge
         * screen's job and not something a migration should do behind someone's back.
         */
        private fun titleCaseCategories(db: SupportSQLiteDatabase) {
            val renames = mutableListOf<Pair<Long, String>>()
            db.query("SELECT id, name FROM categories").use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val name = cursor.getString(1) ?: continue
                    val cased = NameCasing.titleCased(name) ?: continue
                    if (cased != name) renames += id to cased
                }
            }
            for ((id, cased) in renames) {
                val taken = db.query("SELECT COUNT(*) FROM categories WHERE name = ?", arrayOf<Any>(cased))
                    .use { it.moveToFirst() && it.getInt(0) > 0 }
                if (!taken) db.execSQL("UPDATE categories SET name = ? WHERE id = ?", arrayOf<Any>(cased, id))
            }
        }

        private val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                seedUncategorised(db)
            }
        }

        private val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS field_suggestions (" +
                        "recordId INTEGER NOT NULL, fieldKey TEXT NOT NULL, value TEXT, " +
                        "sourceTag TEXT, PRIMARY KEY(recordId, fieldKey))"
                )
                val columns = listOf(
                    "title" to "title", "vendor" to "vendor", "bank" to "bank",
                    "totalAmount" to "totalAmount", "currencyCode" to "currencyCode",
                    "category" to "category", "location" to "location",
                    "dateTime" to "dateTime", "comments" to "comments", "itemsJson" to "items"
                )
                for ((column, key) in columns) {
                    db.execSQL(
                        "INSERT OR REPLACE INTO field_suggestions (recordId, fieldKey, value, sourceTag) " +
                            "SELECT expenseId, '" + key + "', CAST(" + column + " AS TEXT), sourceGroupId " +
                            "FROM pending_field_suggestions WHERE " + column + " IS NOT NULL"
                    )
                }
                db.execSQL("DROP TABLE IF EXISTS pending_field_suggestions")
            }
        }

        private val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS recurring_payments (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "vendorKey TEXT NOT NULL, vendorLabel TEXT NOT NULL, " +
                        "frequency TEXT NOT NULL, interval INTEGER NOT NULL, " +
                        "dueDayOfMonth INTEGER NOT NULL, expectedAmount REAL, currency TEXT, " +
                        "categoryId INTEGER, lastSeenAt INTEGER NOT NULL, " +
                        "occurrences INTEGER NOT NULL, missedCycles INTEGER NOT NULL, " +
                        "confirmedAt INTEGER, notifiedForDueAt INTEGER, " +
                        "dismissed INTEGER NOT NULL, createdAt INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_recurring_payments_vendorKey ON recurring_payments (vendorKey)")
            }
        }

        private val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE expenses ADD COLUMN netAmount REAL")
                db.execSQL("ALTER TABLE expenses ADD COLUMN vatAmount REAL")
            }
        }

        private val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE categories ADD COLUMN isDefault INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "UPDATE categories SET isDefault = 1 WHERE id = " +
                        "(SELECT id FROM categories ORDER BY position ASC, id ASC LIMIT 1)"
                )
            }
        }

        private val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE expenses ADD COLUMN previousBalanceAmount REAL")
                db.execSQL("ALTER TABLE expenses ADD COLUMN totalToPayAmount REAL")
            }
        }

        private val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS remap_rules_new (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "name TEXT NOT NULL, " +
                        "matchJson TEXT NOT NULL, " +
                        "setJson TEXT NOT NULL, " +
                        "origin TEXT NOT NULL, " +
                        "enabled INTEGER NOT NULL, " +
                        "sortOrder INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL, " +
                        "fuzzJson TEXT NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO remap_rules_new (id, name, matchJson, setJson, origin, enabled, sortOrder, updatedAt, fuzzJson) " +
                        "SELECT id, name, matchJson, setJson, 'USER', enabled, sortOrder, updatedAt, fuzzJson " +
                        "FROM remap_rules WHERE origin = 'USER' OR consecutiveCount >= 3"
                )
                db.execSQL("DROP TABLE remap_rules")
                db.execSQL("ALTER TABLE remap_rules_new RENAME TO remap_rules")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS remap_pattern_sightings (" +
                        "patternKey TEXT NOT NULL, " +
                        "recordId INTEGER NOT NULL, " +
                        "fieldId TEXT NOT NULL, " +
                        "beforeText TEXT NOT NULL, " +
                        "afterText TEXT NOT NULL, " +
                        "setFieldId TEXT NOT NULL, " +
                        "setValue TEXT NOT NULL, " +
                        "companionsJson TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(patternKey, recordId))"
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32, MIGRATION_32_33, MIGRATION_33_34)
                // A brand-new install never runs a Migration (Room creates the full current schema
                // directly from the @Entity annotations) — this seeds the same default rules for that
                // path too, so a fresh install and an upgraded one both start with working duplicate
                // protection instead of only the latter getting it.
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        seedDefaultDuplicateRules(db)
                        // Same reason: a first record with nothing to classify it should land
                        // somewhere that says so, not on whichever category was created first.
                        seedUncategorised(db)
                    }
                })
                .build()
        }
    }
}
