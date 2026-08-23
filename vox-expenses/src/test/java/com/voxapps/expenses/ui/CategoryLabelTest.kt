package com.voxapps.expenses.ui

import com.voxapps.expenses.data.Category
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How a category names itself in a line of text.
 *
 * One function rather than the check written at each site, so a list, a chip, a rule and a report
 * all say the same thing about the same category.
 */
class CategoryLabelTest {

    private fun category(name: String, icon: String? = null) =
        Category(id = 1, name = name, colorArgb = 0xFF000000, position = 0, createdAt = 0L, icon = icon)

    @Test
    fun `an icon leads the name`() {
        assertEquals("🛒 Groceries", category("Groceries", "🛒").labelled())
    }

    @Test
    fun `a category without one is just its name`() {
        assertEquals("Groceries", category("Groceries").labelled())
    }

    /** Nothing is inserted where there is nothing to insert, so no row starts with a stray space. */
    @Test
    fun `no separator survives when there is no icon`() {
        assertEquals("Groceries", category("Groceries", null).labelled())
    }

    @Test
    fun `a text shorthand works the same way as an emoji`() {
        assertEquals("RO Utilities", category("Utilities", "RO").labelled())
    }
}
