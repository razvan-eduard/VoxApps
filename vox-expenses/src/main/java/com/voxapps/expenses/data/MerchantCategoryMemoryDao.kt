package com.voxapps.expenses.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MerchantCategoryMemoryDao {
    @Query("SELECT * FROM merchant_category_memory WHERE vendorKey = :vendorKey")
    suspend fun get(vendorKey: String): MerchantCategoryMemory?

    @Query("SELECT categoryId FROM merchant_category_memory WHERE vendorKey = :vendorKey AND consecutiveCount >= :threshold")
    suspend fun getLearnedCategoryId(vendorKey: String, threshold: Int): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(memory: MerchantCategoryMemory)

    @Query("DELETE FROM merchant_category_memory WHERE vendorKey = :vendorKey")
    suspend fun delete(vendorKey: String)

    /** Cascade for a deleted category — a memory row pointing at a category that no longer exists
     *  is meaningless ([MerchantCategoryMemory.categoryId] is NOT NULL, so this is a delete, not a
     *  set-to-null like [ExpenseDao]'s equivalent). */
    @Query("DELETE FROM merchant_category_memory WHERE categoryId = :categoryId")
    suspend fun clearCategory(categoryId: Long)

    @Query("SELECT * FROM merchant_category_memory")
    suspend fun getAll(): List<MerchantCategoryMemory>
}
