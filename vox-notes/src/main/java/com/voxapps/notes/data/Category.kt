package com.voxapps.notes.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A user-defined note category. [colorArgb] is a packed ARGB color used for the sidebar dot and
 * note cards. [position] preserves the user's ordering in the sidebar.
 */
@Entity(tableName = "categories")
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val colorArgb: Long,
    val position: Int = 0,
    val createdAt: Long,
    /** The one category notes fall back to — see [com.voxapps.datahygiene.CategoryFallback].
     *  Exactly one row carries it, and that row cannot be deleted. */
    val isDefault: Boolean = false
)
