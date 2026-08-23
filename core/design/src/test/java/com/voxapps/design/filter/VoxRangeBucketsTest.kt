package com.voxapps.design.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Brackets that split whatever the data actually holds.
 *
 * Fixed brackets cannot: the same list is somebody's weekly shopping and somebody else's rent, and
 * 0–50 is every record for one and none for the other.
 */
class VoxRangeBucketsTest {

    @Test
    fun `the brackets cover every value, first to last`() {
        val buckets = VoxRangeBuckets.of(3.47, 68.12)
        assertTrue(buckets.isNotEmpty())
        assertTrue("starts at or below the smallest", buckets.first().from <= 3.47)
        assertTrue("ends at or above the largest", buckets.last().to >= 68.12)
    }

    @Test
    fun `they run end to end with no gap between them`() {
        val buckets = VoxRangeBuckets.of(0.0, 1000.0)
        buckets.zipWithNext { a, b -> assertEquals(a.to, b.from, 0.0001) }
    }

    @Test
    fun `every value in range lands in one`() {
        val buckets = VoxRangeBuckets.of(3.47, 68.12)
        for (value in listOf(3.47, 10.0, 33.3, 68.12)) {
            assertTrue("$value found", buckets.any { it.contains(value) })
        }
    }

    /** The point of rounding: a boundary a person would have chosen, not the raw extreme. */
    @Test
    fun `boundaries are round numbers`() {
        val buckets = VoxRangeBuckets.of(3.47, 68.12, count = 4)
        assertEquals(0.0, buckets.first().from, 0.0001)
        for (bucket in buckets) {
            assertEquals("a whole step", bucket.to - bucket.from, buckets.first().to - buckets.first().from, 0.0001)
        }
    }

    @Test
    fun `the scale follows the data`() {
        val small = VoxRangeBuckets.of(1.0, 20.0)
        val large = VoxRangeBuckets.of(1000.0, 20000.0)
        assertTrue("a small list gets small brackets", small.first().to <= 10.0)
        assertTrue("a large one gets large brackets", large.first().to >= 1000.0)
    }

    // --- nothing worth dividing ---

    /** One bracket holding everything is not a filter, so none are offered. */
    @Test
    fun `identical values yield nothing`() {
        assertEquals(emptyList<VoxRange>(), VoxRangeBuckets.of(50.0, 50.0))
    }

    @Test
    fun `an inverted or empty span yields nothing`() {
        assertEquals(emptyList<VoxRange>(), VoxRangeBuckets.of(100.0, 10.0))
        assertEquals(emptyList<VoxRange>(), VoxRangeBuckets.of(0.0, 0.0))
    }

    @Test
    fun `numbers that are not numbers yield nothing`() {
        assertEquals(emptyList<VoxRange>(), VoxRangeBuckets.of(Double.NaN, 10.0))
        assertEquals(emptyList<VoxRange>(), VoxRangeBuckets.of(0.0, Double.POSITIVE_INFINITY))
    }

    @Test
    fun `asking for none gives none`() {
        assertEquals(emptyList<VoxRange>(), VoxRangeBuckets.of(0.0, 100.0, count = 0))
    }

    /** A count nobody would ask for must still terminate. */
    @Test
    fun `the bracket count stays readable`() {
        assertTrue(VoxRangeBuckets.of(0.0, 1_000_000.0, count = 1).size <= 12)
        assertTrue(VoxRangeBuckets.of(0.01, 999_999.0).size <= 12)
    }

    // --- what a bracket contains ---

    @Test
    fun `both ends are included`() {
        val range = VoxRange(50.0, 100.0)
        assertTrue(range.contains(50.0))
        assertTrue(range.contains(100.0))
        assertTrue(range.contains(75.0))
        assertFalse(range.contains(49.99))
        assertFalse(range.contains(100.01))
    }

    /** Boundaries are computed by index, so the last one is exact rather than drifted. */
    @Test
    fun `boundaries do not drift`() {
        val buckets = VoxRangeBuckets.of(0.0, 0.7, count = 4)
        assertTrue(buckets.isNotEmpty())
        assertEquals(buckets.last().to, buckets.first().from + buckets.size * (buckets.first().to - buckets.first().from), 0.000001)
    }

    @Test
    fun `negative values are bracketed too`() {
        val buckets = VoxRangeBuckets.of(-100.0, 100.0)
        assertTrue(buckets.first().from <= -100.0)
        assertTrue(buckets.last().to >= 100.0)
        assertTrue(buckets.any { it.contains(-50.0) })
    }

    // --- an open upper bound, for a range somebody typed ---

    @Test
    fun `an infinite upper end contains everything above the floor`() {
        val open = VoxRange(500.0, Double.POSITIVE_INFINITY)
        assertTrue(open.contains(500.0))
        assertTrue(open.contains(1_000_000.0))
        assertFalse(open.contains(499.99))
    }
}
