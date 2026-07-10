package com.voxapps.expenses.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A user-defined expense category. [colorArgb] is a packed ARGB color used for chips/cards.
 * [position] preserves the user's ordering.
 */
@Entity(tableName = "categories")
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val colorArgb: Long,
    val position: Int = 0,
    val createdAt: Long
)
