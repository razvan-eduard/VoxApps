package com.voxapps.expenses.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a record with nothing to classify it lands.
 *
 * Every capture with no opinion about its category takes the fallback, so the fallback is stamped on
 * a great many records at a time — and afterwards a stamped record cannot be told from one that
 * genuinely belongs there. A category naming its own emptiness is the only fallback that stays
 * honest at that volume, so one is seeded and starred.
 */
class UncategorisedFallbackTest {

    private fun category(id: Long, name: String, position: Int, isDefault: Boolean = false) =
        Category(id = id, name = name, colorArgb = 0xFF000000, position = position, createdAt = 0L, isDefault = isDefault)

    /** The rule [ExpensesRepository.defaultCategory] applies. */
    private fun fallbackOf(all: List<Category>): Category? =
        all.firstOrNull { it.isDefault } ?: all.minByOrNull { it.position }

    @Test
    fun `a starred category is the fallback whatever its position`() {
        val all = listOf(
            category(1, "Restaurants", position = 0),
            category(2, "Uncategorised", position = 9, isDefault = true)
        )
        assertEquals("Uncategorised", fallbackOf(all)?.name)
    }

    /** With nothing starred the fallback is positional, which is a rule nobody chose. */
    @Test
    fun `nothing starred leaves the first row answering for everything`() {
        val all = listOf(category(1, "Restaurants", position = 0), category(2, "Groceries", position = 1))
        assertEquals("Restaurants", fallbackOf(all)?.name)
    }

    /** Seeded at -1 so it also wins the positional fallback, not only the starred one. */
    @Test
    fun `the seeded row wins on position too, in case its star is ever cleared`() {
        val all = listOf(
            category(1, "Restaurants", position = 0),
            category(2, "Uncategorised", position = -1)
        )
        assertEquals("Uncategorised", fallbackOf(all)?.name)
    }

    /** A star somebody moved is a choice, and the rule reads it like any other. */
    @Test
    fun `a category someone starred is the fallback`() {
        val all = listOf(
            category(1, "Uncategorised", position = -1),
            category(2, "Groceries", position = 3, isDefault = true)
        )
        assertEquals("Groceries", fallbackOf(all)?.name)
    }

    /** What the settings card shows: the fallback first, everything else after the divider. */
    @Test
    fun `the card lists the fallback first`() {
        val all = listOf(
            category(1, "Restaurants", position = 0),
            category(2, "Groceries", position = 1),
            category(3, "Uncategorised", position = 9, isDefault = true)
        )
        val ordered = listOfNotNull(all.firstOrNull { it.isDefault }) + all.filterNot { it.isDefault }
        assertEquals("Uncategorised", ordered.first().name)
        assertTrue("and it appears exactly once", ordered.count { it.name == "Uncategorised" } == 1)
        assertEquals(all.size, ordered.size)
    }
}
