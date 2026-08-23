package com.voxapps.design.filter

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** A span somebody can pick, both ends included. */
data class VoxRange(val from: Double, val to: Double) {
    fun contains(value: Double): Boolean = value >= from && value <= to
}

/**
 * A few spans covering what the data actually holds.
 *
 * Fixed brackets cannot work: the same list is somebody's weekly shopping and somebody else's rent,
 * and a bracket of 0–50 is every record for one of them and none for the other. Reading the smallest
 * and largest values and dividing the distance between them gives brackets that always split the
 * data somewhere useful, whatever scale it is at.
 *
 * The boundaries are rounded to numbers a person would have chosen — 50, 200, 2500 — rather than to
 * the raw extremes, because a bracket labelled "3.47 – 68.12" is arithmetic showing its working. That
 * costs a little coverage at both ends, which is why the first bracket starts below the smallest
 * value and the last ends above the largest: every record falls in exactly one.
 */
object VoxRangeBuckets {

    /** Enough to divide the data, few enough to read as a row of chips. */
    const val DEFAULT_COUNT = 4

    /** A backstop, not a preference: it only matters if a caller asks for a count near zero. */
    private const val MAX_BUCKETS = 12

    /**
     * [count] brackets spanning [min] to [max], or none when there is nothing to divide.
     *
     * Nothing to divide means: one record, several records of identical value, or numbers that are
     * not numbers. One bracket holding everything is not a filter, so none are offered rather than
     * one that does nothing.
     */
    fun of(min: Double, max: Double, count: Int = DEFAULT_COUNT): List<VoxRange> {
        if (!min.isFinite() || !max.isFinite() || max <= min || count < 1) return emptyList()
        val step = niceStep((max - min) / count)
        if (step <= 0.0 || !step.isFinite()) return emptyList()
        val start = floor(min / step) * step
        val last = ceil(max / step) * step
        // Counted rather than accumulated: adding a step repeatedly drifts, and a boundary that is
        // 149.99999999 both reads wrong and can leave a record in no bracket at all.
        val steps = ceil((last - start) / step).toInt().coerceIn(1, MAX_BUCKETS)
        return (0 until steps).map { i ->
            VoxRange(from = start + i * step, to = start + (i + 1) * step)
        }
    }

    /**
     * The nearest round number at or above [rough]: 1, 2, 5 or 10 times a power of ten.
     *
     * The same set of steps an axis is drawn with, and for the same reason — these are the intervals
     * people count in, so a boundary landing on one is a boundary nobody has to decode.
     */
    private fun niceStep(rough: Double): Double {
        if (rough <= 0.0 || !rough.isFinite()) return 0.0
        val magnitude = 10.0.pow(floor(log10(rough)))
        val normalised = rough / magnitude
        val multiplier = when {
            normalised <= 1.0 -> 1.0
            normalised <= 2.0 -> 2.0
            normalised <= 5.0 -> 5.0
            else -> 10.0
        }
        return multiplier * magnitude
    }
}
