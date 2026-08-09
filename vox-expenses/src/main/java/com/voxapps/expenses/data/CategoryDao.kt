package com.voxapps.expenses.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY position ASC, createdAt ASC")
    fun observeAll(): Flow<List<Category>>

    /** One-shot read for the write paths — `observeAll().first()` would spin up an
     *  InvalidationTracker observer, run the query, then tear it all down again just to read
     *  the current rows once. */
    @Query("SELECT * FROM categories ORDER BY position ASC, createdAt ASC")
    suspend fun getAll(): List<Category>

    @Insert
    suspend fun insert(category: Category): Long

    @Update
    suspend fun update(category: Category)

    @Delete
    suspend fun delete(category: Category)
}
