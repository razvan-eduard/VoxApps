package com.voxapps.design.color

import kotlin.random.Random

/**
 * Shared color math + preset palette for every satellite's category/layer color picker
 * (vox-notes, vox-expenses, vox-calendar previously each carried a byte-for-byte copy of this).
 * No Compose dependency, so the data layer of any app can pick a color without a UI dependency.
 */
object VoxColorPalette {

    /** Number of presets, and the fixed hue step between them — generated rather than hand-picked
     *  hex values, so every pair is guaranteed exactly [PRESET_HUE_STEP] apart instead of relying on
     *  named colors (red/brown/deep-orange etc.) that can drift close together in hue despite looking
     *  distinct by name; [PRESET_SATURATION]/[PRESET_VALUE] match the same vivid, mid-brightness style
     *  [unusedOrRandomColor]'s fallback phase already generates, so presets and generated colors are
     *  visually consistent with each other. */
    private const val PRESET_COUNT = 10
    private const val PRESET_HUE_STEP = 360f / PRESET_COUNT
    private const val PRESET_SATURATION = 0.55f
    private const val PRESET_VALUE = 0.85f

    val presets: List<Long> = (0 until PRESET_COUNT).map { index ->
        hsvToArgb(hue = index * PRESET_HUE_STEP, saturation = PRESET_SATURATION, value = PRESET_VALUE)
    }

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
     * once all presets are taken — a freshly generated color: fixed saturation/value matching the
     * palette's vivid, mid-brightness style so it doesn't look out of place next to the presets, with
     * a hue chosen to be as visually distinct as possible from [existingColors]
     * (farthest-hue-from-nearest-neighbor among [RANDOM_HUE_CANDIDATES] random samples), further
     * biased toward clearing [MIN_PRECEDING_HUE_DISTANCE] from [precedingColor] specifically when
     * one is given — the aggregate-distance optimization alone doesn't guarantee that.
     */
    fun unusedOrRandomColor(existingColors: List<Long>, precedingColor: Long? = null): Long {
        val used = existingColors.toSet()
        val unusedPresets = presets.filter { it !in used }
        if (unusedPresets.isNotEmpty()) return pickPreset(unusedPresets, precedingColor)
        return pickRandomFallback(existingColors, precedingColor)
    }

    private fun pickPreset(unusedPresets: List<Long>, precedingColor: Long?): Long {
        if (precedingColor == null || unusedPresets.size == 1) return unusedPresets.first()
        val precedingHue = argbToHsv(precedingColor).first ?: return unusedPresets.first()
        return unusedPresets.maxBy { candidate -> hueDistance(argbToHsv(candidate).first ?: 0f, precedingHue) }
    }

    private fun pickRandomFallback(existingColors: List<Long>, precedingColor: Long?): Long {
        val existingHues = existingColors.mapNotNull { argbToHsv(it).first }
        val precedingHue = precedingColor?.let { argbToHsv(it).first }
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
        return hsvToArgb(hue = hue, saturation = PRESET_SATURATION, value = PRESET_VALUE)
    }

    /** Shortest distance between two hues on the 360°-wraparound color wheel (0..180). */
    fun hueDistance(a: Float, b: Float): Float {
        val diff = kotlin.math.abs(a - b) % 360f
        return if (diff > 180f) 360f - diff else diff
    }

    /** Recovers (hue 0..360, saturation 0..1, value 0..1) from a packed ARGB color; null hue for
     *  grays (undefined hue) — saturation/value are still meaningful in that case. */
    fun argbToHsv(argbColor: Long): Triple<Float?, Float, Float> {
        val r = ((argbColor shr 16) and 0xFFL).toInt() / 255f
        val g = ((argbColor shr 8) and 0xFFL).toInt() / 255f
        val b = (argbColor and 0xFFL).toInt() / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        val value = max
        val saturation = if (max == 0f) 0f else delta / max
        if (delta == 0f) return Triple(null, saturation, value)
        val hue = when (max) {
            r -> 60f * (((g - b) / delta).mod(6f))
            g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }
        return Triple(if (hue < 0f) hue + 360f else hue, saturation, value)
    }

    /** Standard HSV->RGB conversion, packed as an opaque ARGB Long. No Compose dependency. */
    fun hsvToArgb(hue: Float, saturation: Float, value: Float): Long {
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
