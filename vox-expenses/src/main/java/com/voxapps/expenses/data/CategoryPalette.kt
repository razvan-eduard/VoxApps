package com.voxapps.expenses.data

import kotlin.random.Random

/**
 * Preset category colors as packed ARGB ints (in Longs) — no Compose deps, so the data layer can pick
 * a color for an auto-created category. The UI's `CategoryColors` renders the same values.
 */
object CategoryPalette {
    val argb: List<Long> = listOf(
        0xFFEF5350L, // red
        0xFFEC407AL, // pink
        0xFFAB47BCL, // purple
        0xFF5C6BC0L, // indigo
        0xFF42A5F5L, // blue
        0xFF26A69AL, // teal
        0xFF66BB6AL, // green
        0xFFFFCA28L, // amber
        0xFFFF7043L, // deep orange
        0xFF8D6E63L  // brown
    )

    /** Random-hue candidates sampled per call to [unusedOrRandomColor] — the one farthest in hue
     *  from every existing color wins, so two fresh random colors don't end up looking near-identical
     *  just by chance. */
    private const val RANDOM_HUE_CANDIDATES = 24

    /** How far in hue (of 360°) a freshly generated color must land from [unusedOrRandomColor]'s
     *  optional `precedingColor` to count as "clearly a different color family," not just "not
     *  identical." Only gates the post-preset random-fallback phase. */
    private const val MIN_PRECEDING_HUE_DISTANCE = 90f

    /**
     * The first preset palette color not already used by [existingColors] (preferring whichever
     * unused preset is farthest in hue from [precedingColor], when given, so two colors assigned
     * back-to-back don't end up visually adjacent purely by the presets' fixed array order), or —
     * once all 10 are taken — a freshly generated color: fixed saturation/value matching the
     * palette's vivid, mid-brightness style so it doesn't look out of place next to the presets,
     * with a hue chosen to be as visually distinct as possible from [existingColors]
     * (farthest-hue-from-nearest-neighbor among [RANDOM_HUE_CANDIDATES] random samples), further
     * biased toward clearing [MIN_PRECEDING_HUE_DISTANCE] from [precedingColor] specifically when
     * one is given — the aggregate-distance optimization alone doesn't guarantee that.
     */
    fun unusedOrRandomColor(existingColors: List<Long>, precedingColor: Long? = null): Long {
        val used = existingColors.toSet()
        val unusedPresets = argb.filter { it !in used }
        if (unusedPresets.isNotEmpty()) return pickPreset(unusedPresets, precedingColor)
        return pickRandomFallback(existingColors, precedingColor)
    }

    private fun pickPreset(unusedPresets: List<Long>, precedingColor: Long?): Long {
        if (precedingColor == null || unusedPresets.size == 1) return unusedPresets.first()
        val precedingHue = argbToHue(precedingColor) ?: return unusedPresets.first()
        return unusedPresets.maxBy { candidate -> hueDistance(argbToHue(candidate) ?: 0f, precedingHue) }
    }

    private fun pickRandomFallback(existingColors: List<Long>, precedingColor: Long?): Long {
        val existingHues = existingColors.mapNotNull(::argbToHue)
        val precedingHue = precedingColor?.let(::argbToHue)
        val hue = if (existingHues.isEmpty() && precedingHue == null) {
            Random.nextFloat() * 360f
        } else {
            val scored = (0 until RANDOM_HUE_CANDIDATES).map { Random.nextFloat() * 360f }.map { candidate ->
                val aggregateDistance = if (existingHues.isEmpty()) Float.MAX_VALUE else existingHues.minOf { hueDistance(candidate, it) }
                val precedingDistance = precedingHue?.let { hueDistance(candidate, it) } ?: Float.MAX_VALUE
                Triple(candidate, precedingDistance, aggregateDistance)
            }
            val qualifying = scored.filter { it.second >= MIN_PRECEDING_HUE_DISTANCE }
            if (qualifying.isNotEmpty()) qualifying.maxBy { it.third }.first else scored.maxBy { it.second }.first
        }
        return hsvToArgb(hue = hue, saturation = 0.55f, value = 0.85f)
    }

    /** Shortest distance between two hues on the 360°-wraparound color wheel (0..180). */
    private fun hueDistance(a: Float, b: Float): Float {
        val diff = kotlin.math.abs(a - b) % 360f
        return if (diff > 180f) 360f - diff else diff
    }

    /** Recovers the hue (0..360) from a packed ARGB color; null for grays (undefined hue). */
    private fun argbToHue(argbColor: Long): Float? {
        val r = ((argbColor shr 16) and 0xFFL).toInt() / 255f
        val g = ((argbColor shr 8) and 0xFFL).toInt() / 255f
        val b = (argbColor and 0xFFL).toInt() / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        if (delta == 0f) return null
        val hue = when (max) {
            r -> 60f * (((g - b) / delta).mod(6f))
            g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }
        return if (hue < 0f) hue + 360f else hue
    }

    /** Standard HSV->RGB conversion, packed as an opaque ARGB Long. No Compose dependency. */
    private fun hsvToArgb(hue: Float, saturation: Float, value: Float): Long {
        val c = value * saturation
        val x = c * (1 - kotlin.math.abs((hue / 60f) % 2 - 1))
        val m = value - c
        val (rP, gP, bP) = when {
            hue < 60f -> Triple(c, x, 0f)
            hue < 120f -> Triple(x, c, 0f)
            hue < 180f -> Triple(0f, c, x)
            hue < 240f -> Triple(0f, x, c)
            hue < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val r = ((rP + m) * 255).toInt().coerceIn(0, 255)
        val g = ((gP + m) * 255).toInt().coerceIn(0, 255)
        val b = ((bP + m) * 255).toInt().coerceIn(0, 255)
        return (0xFFL shl 24) or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
    }
}
