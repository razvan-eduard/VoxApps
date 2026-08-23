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
    val createdAt: Long,
    /**
     * The category a record falls back to when nothing chose one — a scan read without a model, or
     * any other creation that has no opinion. Exactly one category carries it at a time, enforced by
     * [com.voxapps.expenses.data.ExpensesRepository] rather than by the schema, and that one cannot
     * be deleted. Mirrors the calendar's main layer, down to the star and the suffix.
     */
    val isDefault: Boolean = false,
    /**
     * A short piece of text shown wherever the category identifies a record — one emoji, in practice.
     *
     * Text rather than a reference into a drawable set, so it survives everywhere the name does: a
     * backup restored on another device, a widget that renders no vectors of its own, a row rendered
     * before any theme is resolved. A set of drawables would need a name-to-resource map that only
     * this app can read, and would leave the widget and the export with a name pointing at nothing.
     *
     * Null is the ordinary case, and means the coloured dot alone identifies the category.
     */
    val icon: String? = null
)
