package com.voxapps.datahygiene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryFallbackTest {

    private data class Cat(val id: Long, val name: String, val fallback: Boolean = false)

    private fun destination(categories: List<Cat>, deleting: Long) =
        CategoryFallback.destinationFor(categories, deleting, { it.id }, { it.fallback })

    @Test
    fun `the records of a deleted category go to the fallback`() {
        val cats = listOf(Cat(1, "Uncategorised", fallback = true), Cat(4, "Cafes"))
        assertEquals(1L, destination(cats, deleting = 4L)?.id)
    }

    /** Nowhere to send them is the one case where records are left without a category. */
    @Test
    fun `with no fallback there is no destination`() {
        assertNull(destination(listOf(Cat(4, "Cafes")), deleting = 4L))
    }

    /** A caller can never be told to move records onto the very row it is deleting — which is what
     *  it would be asking if it tried to delete the fallback itself. */
    @Test
    fun `the fallback is never its own destination`() {
        val cats = listOf(Cat(1, "Uncategorised", fallback = true))
        assertNull(destination(cats, deleting = 1L))
        assertFalse(CategoryFallback.deletable(cats.first()) { it.fallback })
        assertTrue(CategoryFallback.deletable(Cat(4, "Cafes")) { it.fallback })
    }

    /**
     * The seed leaves exactly one star, whatever it found.
     *
     * Read as statements rather than run, since this module has no database — but the order is the
     * meaning: clear, then set, so a moment with two is impossible.
     */
    @Test
    fun `seeding inserts once and leaves exactly one star`() {
        val sql = CategoryFallback.seedStatements(createdAt = 123L)
        assertEquals(3, sql.size)
        assertTrue(sql[0].startsWith("INSERT INTO categories"))
        assertTrue(sql[0].contains("WHERE NOT EXISTS"))
        assertTrue(sql[0].contains(CategoryFallback.SEED_NAME))
        assertTrue(sql[0].contains("123"))
        assertEquals("UPDATE categories SET isDefault = 0", sql[1])
        assertEquals("UPDATE categories SET isDefault = 1 WHERE name = 'Uncategorised'", sql[2])
    }

    @Test
    fun `an app names its own table and column`() {
        val sql = CategoryFallback.seedStatements(table = "buckets", starColumn = "isMain", createdAt = 1L)
        assertTrue(sql.all { it.contains("buckets") })
        assertTrue(sql[2].contains("isMain"))
    }
}
