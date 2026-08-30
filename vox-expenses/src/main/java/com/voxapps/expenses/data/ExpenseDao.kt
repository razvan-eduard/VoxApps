package com.voxapps.expenses.data

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import com.voxapps.design.color.VoxColorPalette

@Dao
interface ExpenseDao {
    /**
     * All expenses joined with category + line items, newest first — for the paths that read the
     * ledger whole regardless of any in-app filter: the widget, the IPC read/export responders, the
     * duplicate machinery. The main list reads [observeFiltered] instead, so its narrowing happens
     * in the query rather than after the rows have been carried up.
     *
     * The archive is the one thing the query itself decides, rather than the layer above. An
     * archived record has to be absent from every list, every total, every budget and every
     * duplicate check, and a rule that has to be remembered at forty call sites is a rule that will
     * be missed at one of them. What genuinely needs all of it — a backup, a sync — says so by name.
     */
    @Transaction
    @Query("SELECT * FROM expenses WHERE archivedAt IS NULL ORDER BY dateTime DESC")
    fun observeExpensesWithDetails(): Flow<List<ExpenseWithDetails>>

    /**
     * The main list, narrowed and ordered by the database: every clause a filter chip can express
     * in SQL — category, date span, amount span, account family, currency — lands here as a WHERE
     * clause, so the rows that leave the database are already the rows the screen will show. What
     * SQL cannot say faithfully (FilterValue's Unicode case-folding, a bank resolved through the
     * record's account) is applied by the state layer's residual pass on this narrowed list.
     *
     * [sort] is a [com.voxapps.expenses.state.SortMode] name; the CASE ladder picks the matching
     * ORDER BY, with newest-first as both the resting order and the tiebreak. [filterByAccount]
     * carries "no account narrowing" separately from [accountIds], because SQL has no empty IN () —
     * the list always holds at least a sentinel, and an account family that resolved to nothing
     * passes a sentinel that matches no row.
     */
    @Transaction
    @Query(
        """
        SELECT * FROM expenses WHERE archivedAt IS NULL
          AND (:categoryId IS NULL OR categoryId = :categoryId)
          AND (:dateFrom IS NULL OR dateTime >= :dateFrom)
          AND (:dateTo IS NULL OR dateTime <= :dateTo)
          AND (:amountMin IS NULL OR totalAmount >= :amountMin)
          AND (:amountMax IS NULL OR totalAmount <= :amountMax)
          AND (:currency IS NULL OR currencyCode = :currency COLLATE NOCASE)
          AND (:filterByAccount = 0 OR bankAccountId IN (:accountIds))
        ORDER BY
          CASE WHEN :sort = 'OLDEST' THEN dateTime END ASC,
          CASE WHEN :sort = 'AMOUNT_ASC' THEN totalAmount END ASC,
          CASE WHEN :sort = 'AMOUNT_DESC' THEN totalAmount END DESC,
          dateTime DESC
        """
    )
    fun observeFiltered(
        categoryId: Long?,
        dateFrom: Long?,
        dateTo: Long?,
        amountMin: Double?,
        amountMax: Double?,
        currency: String?,
        filterByAccount: Boolean,
        accountIds: List<Long>,
        sort: String
    ): Flow<List<ExpenseWithDetails>>

    /**
     * The same narrowing as [observeFiltered], stepped as a paging window instead of materialized
     * whole — the scrolling list reads this; the snapshot the screen's aggregate features hold
     * reads the other. Same clauses by construction: any narrowing added to one belongs in both.
     */
    @Transaction
    @Query(
        """
        SELECT * FROM expenses WHERE archivedAt IS NULL
          AND (:categoryId IS NULL OR categoryId = :categoryId)
          AND (:dateFrom IS NULL OR dateTime >= :dateFrom)
          AND (:dateTo IS NULL OR dateTime <= :dateTo)
          AND (:amountMin IS NULL OR totalAmount >= :amountMin)
          AND (:amountMax IS NULL OR totalAmount <= :amountMax)
          AND (:currency IS NULL OR currencyCode = :currency COLLATE NOCASE)
          AND (:filterByAccount = 0 OR bankAccountId IN (:accountIds))
        ORDER BY
          CASE WHEN :sort = 'OLDEST' THEN dateTime END ASC,
          CASE WHEN :sort = 'AMOUNT_ASC' THEN totalAmount END ASC,
          CASE WHEN :sort = 'AMOUNT_DESC' THEN totalAmount END DESC,
          dateTime DESC
        """
    )
    fun pagedFiltered(
        categoryId: Long?,
        dateFrom: Long?,
        dateTo: Long?,
        amountMin: Double?,
        amountMax: Double?,
        currency: String?,
        filterByAccount: Boolean,
        accountIds: List<Long>,
        sort: String
    ): PagingSource<Int, ExpenseWithDetails>

    /** Every currency the ledger holds — the filter chips' vocabulary, read as one column rather
     *  than through the rows. */
    @Query("SELECT DISTINCT currencyCode FROM expenses WHERE archivedAt IS NULL AND TRIM(currencyCode) != '' ORDER BY currencyCode")
    fun observeCurrenciesInUse(): Flow<List<String>>

    @Query("SELECT DISTINCT location FROM expenses WHERE archivedAt IS NULL AND location IS NOT NULL AND TRIM(location) != '' ORDER BY location")
    fun observeLocationsInUse(): Flow<List<String>>

    /** The ledger's smallest and largest amounts — the two ends the amount buckets are drawn from. */
    @Query("SELECT MIN(totalAmount) AS min, MAX(totalAmount) AS max FROM expenses WHERE archivedAt IS NULL")
    fun observeAmountSpan(): Flow<AmountSpan>

    /** The archive itself, most recently put away first — the order somebody looking for what they
     *  just archived expects, which is not the order they were spent in. */
    @Transaction
    @Query("SELECT * FROM expenses WHERE archivedAt IS NOT NULL ORDER BY archivedAt DESC")
    fun observeArchivedWithDetails(): Flow<List<ExpenseWithDetails>>

    /** Ledger and archive together. For the paths that move the data itself — a backup that skipped
     *  the archive would quietly destroy it, and a sync that skipped it would send it back. */
    @Transaction
    @Query("SELECT * FROM expenses ORDER BY dateTime DESC")
    fun observeAllWithDetails(): Flow<List<ExpenseWithDetails>>

    @Query("SELECT * FROM expenses WHERE archivedAt IS NULL ORDER BY dateTime DESC")
    fun observeAll(): Flow<List<Expense>>

    /** One-shot read for the write/export paths — `observeAll().first()` would spin up an
     *  InvalidationTracker observer, run the query, then tear it all down again. */
    @Query("SELECT * FROM expenses WHERE archivedAt IS NULL ORDER BY dateTime DESC")
    suspend fun getAll(): List<Expense>

    /** Ledger and archive together — see [observeAllWithDetails]. */
    @Query("SELECT * FROM expenses ORDER BY dateTime DESC")
    suspend fun getAllIncludingArchived(): List<Expense>

    @Query("UPDATE expenses SET archivedAt = :at, updatedAt = :now WHERE id IN (:ids)")
    suspend fun setArchivedAt(ids: List<Long>, at: Long?, now: Long)

    /** What has been in the archive longer than it was meant to keep things. Ids rather than rows:
     *  the deletion itself still goes the ordinary way, one tombstone and one attachment cleanup
     *  apiece. */
    @Query("SELECT id FROM expenses WHERE archivedAt IS NOT NULL AND archivedAt < :cutoff")
    suspend fun archivedBefore(cutoff: Long): List<Long>

    @Transaction
    @Query("SELECT * FROM expenses WHERE id = :id")
    suspend fun getWithDetailsById(id: Long): ExpenseWithDetails?

    /** One-shot day-scoped read (e.g. Vox Calendar's day-tap summary via VoxCommand.dateFrom/dateTo) —
     *  a plain SQL range query rather than fetching everything and filtering in memory, since the
     *  caller only wants one day's worth of records. */
    @Query("SELECT * FROM expenses WHERE archivedAt IS NULL AND dateTime BETWEEN :from AND :to ORDER BY dateTime ASC")
    suspend fun getForDateRange(from: Long, to: Long): List<Expense>

    /** Cheap "what color is the top-of-list expense's category" lookup for
     *  [VoxColorPalette.unusedOrRandomColor]'s `precedingColor` param — a single indexed-order
     *  `LIMIT 1` row, not a full fetch+sort of the table. */
    @Query(
        """
        SELECT c.colorArgb FROM expenses e
        INNER JOIN categories c ON c.id = e.categoryId
        WHERE e.categoryId IS NOT NULL AND e.archivedAt IS NULL
        ORDER BY e.dateTime DESC
        LIMIT 1
        """
    )
    suspend fun getMostRecentCategoryColor(): Long?

    @Insert
    suspend fun insert(expense: Expense): Long

    /** Detach all expenses from a category being deleted (code-level ON DELETE SET NULL). */
    /** The same for shops. */
    @Query("SELECT DISTINCT vendor FROM expenses WHERE archivedAt IS NULL AND vendor IS NOT NULL AND TRIM(vendor) != '' ORDER BY vendor")
    fun observeVendorsInUse(): Flow<List<String>>

    /** The devices any record on this phone arrived from — the provenance filter's vocabulary. */
    @Query("SELECT DISTINCT originDeviceName FROM expenses WHERE originDeviceName IS NOT NULL ORDER BY originDeviceName")
    fun observeOriginDevicesInUse(): Flow<List<String>>

    // These bulk relinks bump updatedAt like any other edit: what a record points at is part of
    // what it says, and a peer-to-peer sync can only carry the change if the row looks changed.
    @Query("UPDATE expenses SET categoryId = NULL, updatedAt = :now WHERE categoryId = :categoryId")
    suspend fun clearCategory(categoryId: Long, now: Long)

    /** Reassigns all expenses from one category to another (used by category auto-merge). */
    @Query("UPDATE expenses SET categoryId = :newCategoryId, updatedAt = :now WHERE categoryId = :oldCategoryId")
    suspend fun reassignCategory(oldCategoryId: Long, newCategoryId: Long, now: Long)

    @Query("DELETE FROM expenses")
    suspend fun deleteAll()

    @Query("DELETE FROM expenses WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM expenses WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    /** Read tombstone-worthy uids before a delete-by-ids, since the rows won't exist to query afterwards. */
    @Query("SELECT uid FROM expenses WHERE id IN (:ids)")
    suspend fun getUidsByIds(ids: List<Long>): List<String>

    /** Resolves a peer-to-peer sync delta's uid back to this device's own local row id — needed
     *  before an update (preserve the local id) or a delete-by-uid (Room has no delete-by-uid). */
    /** Lets go of a deleted account without touching the spending it paid for. */
    @Query("UPDATE expenses SET bankAccountId = NULL, updatedAt = :now WHERE bankAccountId = :accountId")
    suspend fun clearBankAccount(accountId: Long, now: Long)

    /** Lets go of a deleted recipient the same way — the records keep their spending and only
     *  stop being transactions, which is what losing the link means (see [Expense.recipientId]). */
    @Query("UPDATE expenses SET recipientId = NULL, updatedAt = :now WHERE recipientId = :recipientId")
    suspend fun clearRecipient(recipientId: Long, now: Long)

    @Query("SELECT id FROM expenses WHERE uid = :uid")
    suspend fun getIdByUid(uid: String): Long?

    @Update
    suspend fun update(expense: Expense)

    @Delete
    suspend fun delete(expense: Expense)

    // --- Sync tombstones (see ExpenseTombstone) ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTombstone(tombstone: ExpenseTombstone)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTombstones(tombstones: List<ExpenseTombstone>)

    @Query("SELECT * FROM expense_tombstones WHERE deletedAt > :since")
    suspend fun getTombstonesSince(since: Long): List<ExpenseTombstone>

    @Query("DELETE FROM expense_tombstones WHERE deletedAt < :before")
    suspend fun deleteStaleTombstones(before: Long)
}

/** MIN/MAX of the ledger's amounts, null when the ledger is empty — see [ExpenseDao.observeAmountSpan]. */
data class AmountSpan(val min: Double?, val max: Double?)
